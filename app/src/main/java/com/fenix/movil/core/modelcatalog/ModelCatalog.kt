// ============================================================================
// ModelCatalog.kt — catálogo dinámico de modelos (F1 pipeline 5 filtros)
// TTL 7 días, cuarentena 3 fallos, fuente de verdad para /v1/models
// ============================================================================

package com.fenix.movil.core.modelcatalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Catálogo de modelos con:
 * - Descubrimiento F1 (GET /v1/models desde proveedores)
 * - TTL 7 días (configurable)
 * - Cuarentena tras 3 fallos consecutivos de verificación F3
 * - Persistencia opcional en disco (JSON) para supervivencia a reinicios
 * - Thread-safe via ConcurrentHashMap + StateFlow para UI
 */
interface ModelCatalog {
    /** Flow reactivo del mapa completo id -> ModelEntry. */
    val catalog: StateFlow<Map<String, ModelEntry>>

    /** Modelos disponibles (no cuarentenados, TTL fresco o sin verificar). */
    val available: StateFlow<List<ModelEntry>>

    /** Inicia descubrimiento F1 desde proveedores configurados. */
    suspend fun discover(): DiscoveryResult

    /** Fuerza verificación F3 de un modelo específico. */
    suspend fun verify(modelId: String): VerificationResult

    /** Obtiene entrada por ID (null si no existe). */
    fun get(modelId: String): ModelEntry?

    /** Registra/actualiza entrada manualmente (ej. T0 triage). */
    suspend fun upsert(entry: ModelEntry): UpsertResult

    /** Elimina entrada del catálogo. */
    suspend fun remove(modelId: String): Boolean

    /** Limpia entradas en cuarentena (reset para reintento). */
    suspend fun clearQuarantine(): Int

    /** Persiste el catálogo a disco (JSON). */
    suspend fun persist(): PersistResult

    /** Carga catálogo desde disco (JSON). */
    suspend fun load(): LoadResult
}

/** Resultado de descubrimiento F1. */
data class DiscoveryResult(
    val discovered: Int,
    val updated: Int,
    val errors: List<String>,
    val durationMs: Long,
)

/** Resultado de verificación F3. */
data class VerificationResult(
    val modelId: String,
    val success: Boolean,
    val entry: ModelEntry?,
    val error: String?,
)

/** Resultado de upsert manual. */
data class UpsertResult(
    val modelId: String,
    val created: Boolean,
    val entry: ModelEntry,
)

/** Resultado de persistencia. */
data class PersistResult(
    val success: Boolean,
    val path: String?,
    val entries: Int,
    val error: String?,
)

/** Resultado de carga. */
data class LoadResult(
    val success: Boolean,
    val path: String?,
    val entries: Int,
    val error: String?,
)

/**
 * Implementación por defecto del ModelCatalog.
 * - Hilos: ConcurrentHashMap para mutaciones, StateFlow para observabilidad.
 * - Persistencia: JSON en filesDir/modelcatalog.json (atómico via archivo temporal).
 * - T0 triage: registro especial de @cf/meta/llama-3.2-3b-instruct (CF Workers AI).
 */
open class DefaultModelCatalog(
    private val persistenceDir: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val quarantineThreshold: Int = QUARANTINE_THRESHOLD,
    private val backgroundScope: kotlinx.coroutines.CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val autoLoad: Boolean = true,
) : ModelCatalog {

    companion object {
        /** TTL por defecto: 7 días en milisegundos. */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 604_800_000 ms
        /** Umbral de cuarentena: 3 fallos consecutivos. */
        const val QUARANTINE_THRESHOLD = 3
        /** Nombre del archivo de persistencia. */
        const val PERSISTENCE_FILENAME = "modelcatalog.json"
        /** Modelo T0 fijo (CF Workers AI triage). */
        const val T0_MODEL_ID = "@cf/meta/llama-3.2-3b-instruct"
        const val T0_PROVIDER = "cloudflare-workers-ai"
    }

    private val entries = ConcurrentHashMap<String, ModelEntry>()
    private val _catalog = MutableStateFlow<Map<String, ModelEntry>>(emptyMap())
    override val catalog: StateFlow<Map<String, ModelEntry>> = _catalog.asStateFlow()

    private val _available = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val available: StateFlow<List<ModelEntry>> = _available.asStateFlow()

    private val persistenceFile: File
    private val loading = AtomicBoolean(false)
    private val persisting = AtomicBoolean(false)

    init {
        persistenceFile = File(persistenceDir, PERSISTENCE_FILENAME)
        // Carga inicial asíncrona (no bloquea constructor)
        if (autoLoad) {
            backgroundScope.launch { load() }
        }
    }

    override fun get(modelId: String): ModelEntry? = entries[modelId]

    override suspend fun discover(): DiscoveryResult {
        val start = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        var discovered = 0
        var updated = 0

        // 1. Registrar/actualizar modelo T0 (triaje) — siempre presente
        val t0Entry = ModelEntry(
            id = T0_MODEL_ID,
            provider = T0_PROVIDER,
            capabilities = listOf("chat", "completion"),
            contextWindow = 8192,
            pricing = Pricing.FREE, // CF Workers AI tiene free tier
            lastVerified = System.currentTimeMillis(), // T0 se considera siempre verificado
            metadata = mapOf(
                "triage" to "true",
                "rpm_limit" to "300",
                "description" to "Modelo de triaje T0 para clasificación rápida (300 RPM en CF Workers AI)"
            ),
        )
        val t0Result = upsert(t0Entry)
        if (t0Result.created) discovered++ else updated++

        // 2. Descubrimiento de proveedores federados (Anillo 2) - stubs para F1
        //    La implementación real de F1 llama a /v1/models de cada proveedor.
        //    Aquí dejamos la estructura; los adaptadores se inyectan en C3/E1.
        val federatedProviders = listOf(
            "puter" to "https://api.puter.ai/v1/models",
            "pollinations" to "https://text.pollinations.ai/v1/models",
            "openrouter" to "https://openrouter.ai/api/v1/models",
            "hf-public" to "https://huggingface.co/api/v1/models",
        )

        for ((provider, url) in federatedProviders) {
            try {
                val models = fetchModelsFromProvider(provider, url)
                for (model in models) {
                    val result = upsert(model)
                    if (result.created) discovered++ else updated++
                }
            } catch (e: Exception) {
                errors.add("$provider: ${e.message}")
            }
        }

        refreshAvailable()
        return DiscoveryResult(discovered, updated, errors, System.currentTimeMillis() - start)
    }

    /** Hook para adaptadores F1 reales (se sobrescribe en tests / C3). */
    protected open suspend fun fetchModelsFromProvider(provider: String, url: String): List<ModelEntry> {
        // Implementación real en ProviderPoolRouter (E1) / adaptadores F1.
        // Aquí devolvemos lista vacía para que el catálogo compile y testee sin red.
        return emptyList()
    }

    override suspend fun verify(modelId: String): VerificationResult {
        val entry = entries[modelId] ?: return VerificationResult(modelId, false, null, "Modelo no encontrado")

        // T0 nunca se verifica (siempre "verificado" por diseño)
        if (modelId == T0_MODEL_ID) {
            return VerificationResult(modelId, true, entry.verifiedNow(), null)
        }

        // Verificación real: llamada ligera al endpoint del proveedor (HEAD/OPTIONS o /models/{id})
        // Stub: simula éxito para tests; implementación real en C3/E1 con adaptadores por proveedor.
        val success = simulateVerification(entry)

        val newEntry = if (success) entry.verifiedNow() else entry.withFailure()
        entries[modelId] = newEntry
        refreshAvailable()

        return VerificationResult(modelId, success, newEntry, if (success) null else "Verificación fallida")
    }

    /** Stub de verificación (sobreescribir en tests / C3). */
    protected open suspend fun simulateVerification(entry: ModelEntry): Boolean = true

    override suspend fun upsert(entry: ModelEntry): UpsertResult {
        val existing = entries.putIfAbsent(entry.id, entry)
        val created = existing == null
        val finalEntry = if (created) entry else entry.copy(
            lastVerified = maxOf(entry.lastVerified, existing.lastVerified),
            failureCount = minOf(entry.failureCount, existing.failureCount),
            quarantined = entry.quarantined || existing.quarantined,
        )
        if (!created) entries[entry.id] = finalEntry
        refreshAvailable()
        return UpsertResult(entry.id, created, finalEntry)
    }

    override suspend fun remove(modelId: String): Boolean {
        val removed = entries.remove(modelId) != null
        if (removed) refreshAvailable()
        return removed
    }

    override suspend fun clearQuarantine(): Int {
        var count = 0
        entries.entries.forEach { e ->
            if (e.value.quarantined) {
                entries[e.key] = e.value.copy(quarantined = false, failureCount = 0)
                count++
            }
        }
        if (count > 0) refreshAvailable()
        return count
    }

    override suspend fun persist(): PersistResult {
        if (persisting.getAndSet(true)) {
            return PersistResult(false, null, 0, "Persistencia en curso")
        }
        return try {
            val json = Json.Default.encodeToString(
                ModelCatalogSnapshot(entries.values.toList(), System.currentTimeMillis())
            )
            val tmpFile = File(persistenceFile.parentFile, "${persistenceFile.name}.tmp")
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(persistenceFile)) {
                throw IOException("No se pudo renombrar archivo temporal")
            }
            PersistResult(true, persistenceFile.absolutePath, entries.size, null)
        } catch (e: Exception) {
            PersistResult(false, null, 0, e.message)
        } finally {
            persisting.set(false)
        }
    }

    override suspend fun load(): LoadResult {
        // Esperar a que cualquier carga en curso termine
        while (loading.get()) {
            delay(10)
        }
        if (loading.getAndSet(true)) {
            // Ya está cargando, esperar a que termine
            while (loading.get()) {
                delay(10)
            }
            // Verificar si la carga anterior tuvo éxito
            if (entries.isNotEmpty()) {
                return LoadResult(true, persistenceFile.absolutePath, entries.size, null)
            }
        }
        return try {
            if (!persistenceFile.exists()) {
                return LoadResult(true, persistenceFile.absolutePath, 0, null)
            }
            val json = persistenceFile.readText()
            val snapshot = Json.Default.decodeFromString<ModelCatalogSnapshot>(json)
            entries.clear()
            snapshot.entries.forEach { entries[it.id] = it }
            refreshAvailable()
            LoadResult(true, persistenceFile.absolutePath, entries.size, null)
        } catch (e: Exception) {
            LoadResult(false, null, 0, e.message)
        } finally {
            loading.set(false)
        }
    }

    private fun refreshAvailable() {
        val now = System.currentTimeMillis()
        val list = entries.values
            .filter { !it.quarantined && (it.lastVerified == 0L || (now - it.lastVerified) < ttlMs) }
            .sortedByDescending { it.lastVerified }
            .toList()
        _available.value = list
        _catalog.value = entries.toMap()
    }

    /** Snapshot serializable para persistencia. */
    @kotlinx.serialization.Serializable
    private data class ModelCatalogSnapshot(
        val entries: List<ModelEntry>,
        val timestamp: Long,
    )
}

