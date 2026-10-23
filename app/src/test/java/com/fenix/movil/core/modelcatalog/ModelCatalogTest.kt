// ============================================================================
// ModelCatalogTest.kt — verificación de C1 (gate: testDebugUnitTest --tests '*ModelCatalog*')
// Cobertura: TTL 7 días, cuarentena 3 fallos, upsert, verify, discover, persistencia
// ============================================================================

package com.fenix.movil.core.modelcatalog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Helper para llamar funciones suspendidas en tests
private fun <T> runTest(block: suspend () -> T): T = runBlocking { block() }

class ModelCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var persistenceDir: File
    private lateinit var catalog: DefaultModelCatalog

    @Before
    fun setUp() {
        persistenceDir = tmp.newFolder("modelcatalog")
        catalog = DefaultModelCatalog(
            persistenceDir = persistenceDir,
            ttlMs = 10_000,
            quarantineThreshold = 3,
            backgroundScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
        // Esperar carga inicial
        runBlocking { delay(10) }
    }

    @After
    fun tearDown() {
        // No cleanup needed for Dispatchers.Unconfined
    }

    // Helper para avanzar tiempo virtual (no-op con Dispatchers.Unconfined)
    private fun advanceUntilIdle() = runBlocking { delay(1) }

    // ------------------------------------------------------------------
    // Upsert / Get / Available
    // ------------------------------------------------------------------

    @Test
    fun `upsert crea nueva entrada y aparece en available`() = runTest {
        val entry = ModelEntry(
            id = "test-model-1",
            provider = "test-provider",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
        )

        val result = catalog.upsert(entry)

        assertThat(result.created).isTrue()
        assertThat(result.entry.id).isEqualTo("test-model-1")
        assertThat(catalog.get("test-model-1")).isNotNull()
        assertThat(catalog.available.value.size).isEqualTo(1)
    }

    @Test
    fun `upsert actualiza entrada existente preservando lastVerified mayor`() = runTest {
        val entry1 = ModelEntry(
            id = "test-model-1",
            provider = "test-provider",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            lastVerified = 1000,
        )
        catalog.upsert(entry1)
        advanceUntilIdle()

        val entry2 = entry1.copy(lastVerified = 2000, capabilities = listOf("chat", "completion"))
        val result = catalog.upsert(entry2)

        assertThat(result.created).isFalse()
        assertThat(result.entry.capabilities).containsExactly("chat", "completion").inOrder()
        assertThat(result.entry.lastVerified).isEqualTo(2000)
    }

    @Test
    fun `get retorna null para ID inexistente`() = runTest {
        assertThat(catalog.get("no-existe")).isNull()
    }

    @Test
    fun `available excluye entradas en cuarentena`() = runTest {
        val entry = ModelEntry(
            id = "quarantined-model",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            quarantined = true,
        )
        catalog.upsert(entry)
        advanceUntilIdle()

        assertThat(catalog.available.value.isEmpty()).isTrue()
        assertThat(catalog.get("quarantined-model")?.quarantined).isTrue()
    }

    @Test
    fun `available excluye entradas con TTL vencido`() = runTest {
        val entry = ModelEntry(
            id = "stale-model",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            lastVerified = System.currentTimeMillis() - 20_000,
        )
        catalog.upsert(entry)
        advanceUntilIdle()

        assertThat(catalog.available.value.isEmpty()).isTrue()
    }

    @Test
    fun `available incluye entradas sin lastVerified (nunca verificadas)`() = runTest {
        val entry = ModelEntry(
            id = "never-verified",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            lastVerified = 0,
        )
        catalog.upsert(entry)
        advanceUntilIdle()

        assertThat(catalog.available.value.size).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Verificación y Cuarentena (3 fallos)
    // ------------------------------------------------------------------

    @Test
    fun `verify exitoso resetea failureCount y actualiza lastVerified`() = runTest {
        val entry = ModelEntry(
            id = "verify-model",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            failureCount = 2,
            lastVerified = 1000,
        )
        catalog.upsert(entry)
        advanceUntilIdle()

        val result = catalog.verify("verify-model")

        assertThat(result.success).isTrue()
        assertThat(result.entry?.failureCount).isEqualTo(0)
        assertThat(result.entry?.quarantined).isFalse()
        assertThat(result.entry?.lastVerified).isGreaterThan(1000)
    }

    @Test
    fun `verify fallido incrementa failureCount`() = runTest {
        val failingCatalog = object : DefaultModelCatalog(
            persistenceDir, 10_000, 3, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        ) {
            override suspend fun simulateVerification(entry: ModelEntry): Boolean = false
        }
        runTest { }

        val entry = ModelEntry(
            id = "fail-model",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
        )
        failingCatalog.upsert(entry)
        runTest { }

        val result1 = failingCatalog.verify("fail-model")
        assertThat(result1.success).isFalse()
        assertThat(result1.entry?.failureCount).isEqualTo(1)
        assertThat(result1.entry?.quarantined).isFalse()

        val result2 = failingCatalog.verify("fail-model")
        assertThat(result2.entry?.failureCount).isEqualTo(2)

        val result3 = failingCatalog.verify("fail-model")
        assertThat(result3.entry?.failureCount).isEqualTo(3)
        assertThat(result3.entry?.quarantined).isTrue()
    }

    @Test
    fun `cuarentena a 3 fallos consecutivos`() = runTest {
        val failingCatalog = object : DefaultModelCatalog(
            persistenceDir, 10_000, 3, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        ) {
            override suspend fun simulateVerification(entry: ModelEntry): Boolean = false
        }
        runTest { }

        val entry = ModelEntry(
            id = "quarantine-model",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
        )
        failingCatalog.upsert(entry)
        runTest { }

        repeat(3) { failingCatalog.verify("quarantine-model") }
        runTest { }

        assertThat(failingCatalog.get("quarantine-model")?.quarantined).isTrue()
        assertThat(failingCatalog.available.value.isEmpty()).isTrue()
    }

    @Test
    fun `verify modelo inexistente retorna error`() = runTest {
        val result = catalog.verify("no-existe")

        assertThat(result.success).isFalse()
        assertThat(result.error).contains("no encontrado")
        assertThat(result.entry).isNull()
    }

    @Test
    fun `T0 modelo nunca se cuarentena (verify siempre exitoso)`() = runTest {
        val failingCatalog = object : DefaultModelCatalog(
            persistenceDir, 10_000, 3, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        ) {
            override suspend fun simulateVerification(entry: ModelEntry): Boolean = false
        }
        runTest { }

        val t0Entry = ModelEntry(
            id = DefaultModelCatalog.T0_MODEL_ID,
            provider = DefaultModelCatalog.T0_PROVIDER,
            capabilities = listOf("chat"),
            contextWindow = 8192,
            pricing = Pricing.FREE,
            lastVerified = System.currentTimeMillis(),
        )
        failingCatalog.upsert(t0Entry)
        runTest { }

        repeat(5) { failingCatalog.verify(DefaultModelCatalog.T0_MODEL_ID) }
        runTest { }

        val t0 = failingCatalog.get(DefaultModelCatalog.T0_MODEL_ID)
        assertThat(t0?.quarantined).isFalse()
        assertThat(t0?.failureCount).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // Clear Quarantine
    // ------------------------------------------------------------------

    @Test
    fun `clearQuarantine libera modelos en cuarentena`() = runTest {
        val failingCatalog = object : DefaultModelCatalog(
            persistenceDir, 10_000, 3, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        ) {
            override suspend fun simulateVerification(entry: ModelEntry): Boolean = false
        }
        runTest { }

        val entry = ModelEntry(
            id = "to-clear",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
        )
        failingCatalog.upsert(entry)
        runTest { }

        repeat(3) { failingCatalog.verify("to-clear") }
        runTest { }
        assertThat(failingCatalog.get("to-clear")?.quarantined).isTrue()

        val cleared = failingCatalog.clearQuarantine()
        runTest { }

        assertThat(cleared).isEqualTo(1)
        assertThat(failingCatalog.get("to-clear")?.quarantined).isFalse()
        assertThat(failingCatalog.get("to-clear")?.failureCount).isEqualTo(0)
        assertThat(failingCatalog.available.value.size).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Remove
    // ------------------------------------------------------------------

    @Test
    fun `remove elimina entrada y actualiza available`() = runTest {
        val entry = ModelEntry(
            id = "to-remove",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
        )
        catalog.upsert(entry)
        runTest { }
        assertThat(catalog.available.value.size).isEqualTo(1)

        val removed = catalog.remove("to-remove")
        runTest { }

        assertThat(removed).isTrue()
        assertThat(catalog.get("to-remove")).isNull()
        assertThat(catalog.available.value.isEmpty()).isTrue()
    }

    @Test
    fun `remove ID inexistente retorna false`() = runTest {
        assertThat(catalog.remove("no-existe")).isFalse()
    }

    // ------------------------------------------------------------------
    // Persistencia (load / persist)
    // ------------------------------------------------------------------

    @Test
    fun `persist guarda y load restaura entradas`() = runTest {
        val now = System.currentTimeMillis()
        val entry1 = ModelEntry(
            id = "persist-1",
            provider = "test",
            capabilities = listOf("chat"),
            contextWindow = 4096,
            pricing = Pricing.FREE,
            lastVerified = now,
        )
        val entry2 = ModelEntry(
            id = "persist-2",
            provider = "test",
            capabilities = listOf("completion"),
            contextWindow = 8192,
            pricing = Pricing(inputPer1M = 0.5, outputPer1M = 1.5),
            lastVerified = now,
            failureCount = 1,
            quarantined = false,
        )
        catalog.upsert(entry1)
        catalog.upsert(entry2)

        val persistResult = catalog.persist()

        assertThat(persistResult.success).isTrue()
        assertThat(persistResult.entries).isEqualTo(2)

        // Verificar que el archivo existe y tiene contenido
        val persistenceFile = File(persistenceDir, "modelcatalog.json")
        assertThat(persistenceFile.exists()).isTrue()
        val fileContent = persistenceFile.readText()
        assertThat(fileContent).isNotEmpty()

        // Crear nuevo catálogo sin auto-carga y forzar carga síncrona
        val catalog2 = DefaultModelCatalog(
            persistenceDir = persistenceDir,
            ttlMs = 10_000,
            quarantineThreshold = 3,
            backgroundScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            autoLoad = false,
        )
        val loadResult = catalog2.load()
        // Verificar resultado de carga
        println("Load result: success=${loadResult.success}, entries=${loadResult.entries}, error=${loadResult.error}, path=${loadResult.path}")
        assertThat(loadResult.success).isTrue()
        assertThat(loadResult.entries).isEqualTo(2)
        assertThat(loadResult.error).isNull()

        // Verificar que los datos se cargaron correctamente
        assertThat(catalog2.get("persist-1")?.lastVerified).isEqualTo(now)
        assertThat(catalog2.get("persist-2")?.failureCount).isEqualTo(1)
        assertThat(catalog2.get("persist-2")?.pricing?.inputPer1M).isEqualTo(0.5)
        assertThat(catalog2.available.value.size).isEqualTo(2)
    }

    @Test
    fun `load en directorio vacío no falla`() = runTest {
        val emptyDir = tmp.newFolder("empty-catalog")
        val catalog2 = DefaultModelCatalog(
            persistenceDir = emptyDir,
            ttlMs = 10_000,
            quarantineThreshold = 3,
            backgroundScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
        runTest { }

        assertThat(catalog2.catalog.value.isEmpty()).isTrue()
        assertThat(catalog2.available.value.isEmpty()).isTrue()
    }

    // ------------------------------------------------------------------
    // Discover (F1 stub)
    // ------------------------------------------------------------------

    @Test
    fun `discover registra modelo T0 por defecto`() = runTest {
        val result = catalog.discover()
        runTest { }

        assertThat(result.discovered).isAtLeast(1)
        assertThat(catalog.get(DefaultModelCatalog.T0_MODEL_ID)).isNotNull()
        val t0 = catalog.get(DefaultModelCatalog.T0_MODEL_ID)!!
        assertThat(t0.provider).isEqualTo(DefaultModelCatalog.T0_PROVIDER)
        assertThat(t0.metadata["triage"]).isEqualTo("true")
        assertThat(t0.metadata["rpm_limit"]).isEqualTo("300")
    }

    // ------------------------------------------------------------------
    // Modelo Entry helpers
    // ------------------------------------------------------------------

    @Test
    fun `ModelEntry_isFresh respeta TTL`() = runTest {
        val now = System.currentTimeMillis()
        val fresh = ModelEntry(
            id = "fresh", provider = "test", capabilities = listOf(),
            contextWindow = 4096, pricing = Pricing.FREE,
            lastVerified = now - 5_000,
        )
        val stale = ModelEntry(
            id = "stale", provider = "test", capabilities = listOf(),
            contextWindow = 4096, pricing = Pricing.FREE,
            lastVerified = now - 20_000,
        )

        assertThat(fresh.isFresh(10_000)).isTrue()
        assertThat(stale.isFresh(10_000)).isFalse()
    }

    @Test
    fun `ModelEntry_verifiedNow resetea failureCount y cuarentena`() = runTest {
        val entry = ModelEntry(
            id = "test", provider = "test", capabilities = listOf(),
            contextWindow = 4096, pricing = Pricing.FREE,
            lastVerified = 1000, failureCount = 2, quarantined = true,
        )

        val verified = entry.verifiedNow()

        assertThat(verified.failureCount).isEqualTo(0)
        assertThat(verified.quarantined).isFalse()
        assertThat(verified.lastVerified).isGreaterThan(1000)
    }

    @Test
    fun `ModelEntry_withFailure incrementa y cuarentena en threshold`() = runTest {
        var entry = ModelEntry(
            id = "test", provider = "test", capabilities = listOf(),
            contextWindow = 4096, pricing = Pricing.FREE,
        )

        entry = entry.withFailure()
        assertThat(entry.failureCount).isEqualTo(1)
        assertThat(entry.quarantined).isFalse()

        entry = entry.withFailure()
        assertThat(entry.failureCount).isEqualTo(2)
        assertThat(entry.quarantined).isFalse()

        entry = entry.withFailure()
        assertThat(entry.failureCount).isEqualTo(3)
        assertThat(entry.quarantined).isTrue()
    }
}