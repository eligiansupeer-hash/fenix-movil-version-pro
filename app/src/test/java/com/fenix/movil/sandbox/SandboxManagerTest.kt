// ============================================================================
// SandboxManagerTest.kt — verificación de B3 (gate: testDebugUnitTest)
//
// Cobertura: construcción del argv proot, aislamiento de entornos, máquina
// de estados STOPPED/BOOTING/RUNNING/ERROR, validaciones que impiden lanzar,
// parada/dstrucción, resolución DNS y un ciclo real con ProcessBuilder.
// ============================================================================

package com.fenix.movil.sandbox

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var proot: File

    @Before
    fun setUp() {
        rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "root").mkdirs()
        File(rootfs, "etc/debian_version").writeText("12.15\n")
        proot = tmp.newFile("libproot.so")
        proot.setExecutable(true)
    }

    private fun config(
        transform: SandboxConfig.() -> SandboxConfig = { this },
    ): SandboxConfig = SandboxConfig(
        prootBinary = proot,
        rootfsDir = rootfs,
        bootGraceMs = BOOT_GRACE_MS,
        pollIntervalMs = POLL_MS,
        stopGraceMs = STOP_GRACE_MS,
    ).transform()

    // ------------------------------------------------------------------
    // Estados y flujo
    // ------------------------------------------------------------------

    @Test
    fun `el estado inicial es STOPPED sin error`() = runTest {
        val manager = SandboxManager(config(), FakeLauncher())

        assertThat(manager.state.value).isEqualTo(SandboxState.STOPPED)
        assertThat(manager.lastError.value).isNull()
        assertThat(manager.isRunning).isFalse()
    }

    @Test
    fun `los estados se exponen como StateFlow`() {
        val manager = SandboxManager(config(), FakeLauncher())

        assertThat(manager.state).isInstanceOf(StateFlow::class.java)
        assertThat(manager.lastError).isInstanceOf(StateFlow::class.java)
    }

    @Test
    fun `start exitoso transita por BOOTING hasta RUNNING`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)
        var stateOnLaunch: SandboxState? = null
        launcher.onLaunch = { stateOnLaunch = manager.state.value }

        val observed = mutableListOf<SandboxState>()
        backgroundScope.launch { manager.state.toList(observed) }
        yield()

        val result = manager.start()
        yield()

        assertThat(result).isEqualTo(SandboxState.RUNNING)
        assertThat(stateOnLaunch).isEqualTo(SandboxState.BOOTING)
        assertThat(observed)
            .containsExactly(
                SandboxState.STOPPED,
                SandboxState.BOOTING,
                SandboxState.RUNNING,
            )
            .inOrder()
        assertThat(manager.state.value).isEqualTo(SandboxState.RUNNING)
        assertThat(manager.lastError.value).isNull()
        assertThat(manager.isRunning).isTrue()
        assertThat(launcher.launches).isEqualTo(1)
        assertThat(launcher.lastCommand).isEqualTo(config().buildCommand())
        assertThat(launcher.lastEnvironment).isEqualTo(config().hostEnvironment())
        assertThat(launcher.lastWorkingDir).isEqualTo(rootfs)
    }

    @Test
    fun `start en RUNNING no relanza el proceso`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)
        manager.start()

        val second = manager.start()

        assertThat(second).isEqualTo(SandboxState.RUNNING)
        assertThat(launcher.launches).isEqualTo(1)
        assertThat(manager.state.value).isEqualTo(SandboxState.RUNNING)
    }

    @Test
    fun `start en ERROR reinicia con un proceso nuevo`() = runTest {
        val launcher = FakeLauncher(handles = listOf(FakeProcess(exitCode = 127)))
        val manager = SandboxManager(config(), launcher, backgroundScope)
        assertThat(manager.start()).isEqualTo(SandboxState.ERROR)
        assertThat(manager.lastError.value).isNotEmpty()

        val second = manager.start()

        assertThat(second).isEqualTo(SandboxState.RUNNING)
        assertThat(launcher.launches).isEqualTo(2)
        assertThat(manager.lastError.value).isNull()
    }

    @Test
    fun `stop destruye el proceso y vuelve a STOPPED`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)
        manager.start()
        val handle = requireNotNull(launcher.lastHandle)

        val result = manager.stop()

        assertThat(result).isEqualTo(SandboxState.STOPPED)
        assertThat(manager.state.value).isEqualTo(SandboxState.STOPPED)
        assertThat(handle.destroyed).isTrue()
        assertThat(handle.destroyCount).isEqualTo(1)
        assertThat(manager.lastError.value).isNull()
    }

    @Test
    fun `stop en STOPPED es idempotente`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)

        manager.stop()
        manager.stop()

        assertThat(manager.state.value).isEqualTo(SandboxState.STOPPED)
        assertThat(launcher.launches).isEqualTo(0)
    }

    @Test
    fun `salida inesperada con codigo distinto de cero pasa a ERROR`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)
        manager.start()
        val handle = requireNotNull(launcher.lastHandle)
        handle.stderr = "Segmentation fault"
        handle.exitCode = 139

        avanzarSondeo()

        assertThat(manager.state.value).isEqualTo(SandboxState.ERROR)
        assertThat(manager.lastError.value).contains("139")
        assertThat(manager.lastError.value).contains("Segmentation fault")
    }

    @Test
    fun `salida limpia con codigo cero pasa a STOPPED`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)
        manager.start()
        val handle = requireNotNull(launcher.lastHandle)
        handle.exitCode = 0

        avanzarSondeo()

        assertThat(manager.state.value).isEqualTo(SandboxState.STOPPED)
        assertThat(manager.lastError.value).isNull()
    }

    // ------------------------------------------------------------------
    // Validación previa al lanzamiento (no se lanza nada si algo falla)
    // ------------------------------------------------------------------

    @Test
    fun `start sin rootfs falla sin lanzar proceso`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(
            config { copy(rootfsDir = File(tmp.root, "no-existe")) },
            launcher,
            backgroundScope,
        )

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(manager.lastError.value).contains("rootfs no encontrado")
    }

    @Test
    fun `start con rootfs incompleto falla sin lanzar proceso`() = runTest {
        val incompleto = tmp.newFolder("rootfs-incompleto")
        val launcher = FakeLauncher()
        val manager = SandboxManager(
            config { copy(rootfsDir = incompleto) },
            launcher,
            backgroundScope,
        )

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(manager.lastError.value).contains("etc/debian_version")
    }

    @Test
    fun `start sin binario proot falla sin lanzar proceso`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(
            config { copy(prootBinary = File(tmp.root, "no-existe.so")) },
            launcher,
            backgroundScope,
        )

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(manager.lastError.value).contains("binario de proot ausente")
    }

    @Test
    fun `start con proot no ejecutable falla sin lanzar proceso`() = runTest {
        proot.setExecutable(false)
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(manager.lastError.value).contains("no ejecutable")
    }

    @Test
    fun `start con pollInterval nulo falla sin entrar en bucle infinito`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(
            config { copy(pollIntervalMs = 0L) },
            launcher,
            backgroundScope,
        )

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(launcher.launches).isEqualTo(0)
        assertThat(manager.lastError.value).contains("pollIntervalMs")
    }

    @Test
    fun `fallo del launcher deja el estado en ERROR`() = runTest {
        val launcher = FakeLauncher(failure = IOException("spawn falló"))
        val manager = SandboxManager(config(), launcher, backgroundScope)

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(manager.state.value).isEqualTo(SandboxState.ERROR)
        assertThat(manager.lastError.value).contains("spawn falló")
    }

    @Test
    fun `proceso que muere en la ventana de gracia queda en ERROR con stderr`() = runTest {
        val muerto = FakeProcess(exitCode = 127).apply {
            stderr = "bash: /bin/bash: no existe"
        }
        val launcher = FakeLauncher(handles = listOf(muerto))
        val manager = SandboxManager(config(), launcher, backgroundScope)

        val result = manager.start()

        assertThat(result).isEqualTo(SandboxState.ERROR)
        assertThat(manager.lastError.value).contains("127")
        assertThat(manager.lastError.value).contains("no existe")
        assertThat(muerto.destroyed).isTrue()
        assertThat(manager.isRunning).isFalse()
    }

    // ------------------------------------------------------------------
    // Montaje proot: argv, entornos y DNS
    // ------------------------------------------------------------------

    @Test
    fun `buildCommand monta rootfs binds cwd entorno y comando guest`() {
        val command = config().buildCommand()

        assertThat(command[0]).isEqualTo(proot.absolutePath)
        assertThat(command[1]).isEqualTo("-r")
        assertThat(command[2]).isEqualTo(rootfs.absolutePath)

        val envIndex = command.indexOf("/usr/bin/env")
        assertThat(envIndex).isGreaterThan(0)
        assertThat(command.indexOf("-r")).isLessThan(envIndex)
        assertThat(command.indexOf("-b")).isLessThan(envIndex)
        assertThat(command.indexOf("-w")).isLessThan(envIndex)
        assertThat(command).contains("/dev")
        assertThat(command).contains("/proc")
        assertThat(command).contains("/sys")
        assertThat(command).contains("-0")
        assertThat(command).contains("--link2symlink")
        assertThat(command).contains("--kill-on-exit")
        assertThat(command[command.indexOf("-w") + 1]).isEqualTo("/root")

        val initIndex = command.indexOf("-i")
        assertThat(initIndex).isEqualTo(envIndex + 1)
        val guestPath = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        assertThat(command).contains("HOME=/root")
        assertThat(command).contains("USER=root")
        assertThat(command).contains("TERM=xterm-256color")
        assertThat(command).contains("LANG=C.UTF-8")
        assertThat(command).contains(guestPath)
        assertThat(command.indexOf("HOME=/root")).isGreaterThan(initIndex)
        assertThat(command.indexOf(guestPath)).isGreaterThan(initIndex)

        assertThat(command.subList(command.size - 2, command.size))
            .containsExactly("/bin/bash", "--login")
            .inOrder()
    }

    @Test
    fun `el entorno host se reemplaza y el guest queda aislado con env -i`() {
        val host = config().hostEnvironment()
        assertThat(host.keys)
            .containsExactly("PATH", "LANG", "ANDROID_ROOT", "ANDROID_DATA")

        val guest = config { copy(guestEnv = mapOf("FENIX_MODO" to "prueba")) }
            .guestEnvironment()
        assertThat(guest.keys).containsExactly(
            "HOME",
            "LOGNAME",
            "USER",
            "SHELL",
            "TERM",
            "LANG",
            "TMPDIR",
            "PATH",
            "FENIX_MODO",
        )
        assertThat(guest["FENIX_MODO"]).isEqualTo("prueba")
        assertThat(guest).doesNotContainKey("ANDROID_ROOT")
        assertThat(guest["HOME"]).isEqualTo("/root")
    }

    @Test
    fun `bind con ruta host relativa es rechazado`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            BindMount("dev")
        }
        assertThat(thrown.message).contains("absoluta")
    }

    @Test
    fun `escribe resolv_conf con los dns del dispositivo`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(
            config { copy(dnsServers = listOf("10.0.0.1", "10.0.0.2")) },
            launcher,
            backgroundScope,
        )

        manager.start()

        val resolv = File(rootfs, "etc/resolv.conf")
        assertThat(resolv.isFile).isTrue()
        val content = resolv.readText()
        assertThat(content).contains("nameserver 10.0.0.1")
        assertThat(content).contains("nameserver 10.0.0.2")
    }

    @Test
    fun `sin dns configurados no escribe resolv_conf`() = runTest {
        val launcher = FakeLauncher()
        val manager = SandboxManager(config(), launcher, backgroundScope)

        manager.start()

        assertThat(File(rootfs, "etc/resolv.conf").exists()).isFalse()
    }

    // ------------------------------------------------------------------
    // Ciclo real con ProcessBuilder (sin dobles)
    // ------------------------------------------------------------------

    @Test
    fun `ciclo completo con el lanzador real`() = runTest {
        Assume.assumeTrue("se requiere /bin/sh en el anfitrión", File("/bin/sh").canExecute())
        val script = File(tmp.root, "sandbox-real.sh")
        script.writeText(
            "#!/bin/sh\n" +
                "echo 'sandbox listo' >&2\n" +
                "exec /bin/sleep 300\n",
        )
        script.setExecutable(true)

        val manager = SandboxManager(
            config { copy(prootBinary = script, bootGraceMs = BOOT_GRACE_REAL_MS) },
            ProcessBuilderLauncher(),
            backgroundScope,
        )

        val started = manager.start()
        assertThat(started).isEqualTo(SandboxState.RUNNING)
        assertThat(manager.state.value).isEqualTo(SandboxState.RUNNING)

        val stopped = manager.stop()
        assertThat(stopped).isEqualTo(SandboxState.STOPPED)
        assertThat(manager.state.value).isEqualTo(SandboxState.STOPPED)
        assertThat(manager.lastError.value).isNull()
    }

    /** Avanza el tiempo virtual hasta que el supervisor haya podido sondear. */
    private suspend fun avanzarSondeo() =
        kotlinx.coroutines.delay(BOOT_GRACE_MS + 3 * POLL_MS)

    // ------------------------------------------------------------------
    // Dobles
    // ------------------------------------------------------------------

    private class FakeProcess(var exitCode: Int? = null) : SandboxProcessHandle {
        var destroyed = false
        var destroyCount = 0
        var stdout = ""
        var stderr = ""

        override val isAlive: Boolean
            get() = exitCode == null

        override fun pollExitCode(): Int? = exitCode

        override fun destroy(graceMillis: Long) {
            destroyCount += 1
            destroyed = true
            if (exitCode == null) exitCode = 143
        }

        override fun stdoutTail(maxChars: Int): String = stdout.takeLast(maxChars)

        override fun stderrTail(maxChars: Int): String = stderr.takeLast(maxChars)
    }

    private class FakeLauncher(
        private val handles: List<FakeProcess> = emptyList(),
        private val failure: IOException? = null,
    ) : SandboxProcessLauncher {
        var launches = 0
            private set
        var lastCommand: List<String> = emptyList()
            private set
        var lastEnvironment: Map<String, String> = emptyMap()
            private set
        var lastWorkingDir: File? = null
            private set
        var lastHandle: FakeProcess? = null
            private set
        var onLaunch: (() -> Unit)? = null

        override fun launch(
            command: List<String>,
            environment: Map<String, String>,
            workingDir: File,
        ): SandboxProcessHandle {
            launches += 1
            lastCommand = command
            lastEnvironment = environment
            lastWorkingDir = workingDir
            onLaunch?.invoke()
            failure?.let { throw it }
            val handle = handles.getOrNull(launches - 1) ?: FakeProcess()
            lastHandle = handle
            return handle
        }
    }

    private companion object {
        const val BOOT_GRACE_MS = 200L
        const val POLL_MS = 50L
        const val STOP_GRACE_MS = 100L
        const val BOOT_GRACE_REAL_MS = 5_000L
    }
}
