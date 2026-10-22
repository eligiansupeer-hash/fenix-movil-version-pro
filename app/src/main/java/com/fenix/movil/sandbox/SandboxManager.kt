// ============================================================================
// SandboxManager.kt — montaje, arranque y parada del sandbox proot (plan B3)
// ============================================================================

package com.fenix.movil.sandbox

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orquestador del sandbox Linux propio (tarea B3).
 *
 * Responsabilidades:
 * - **Montaje proot**: argv con rootfs, bind mounts, cwd, `-0`,
 *   extensiones y entorno guest aislado vía `env -i` (ver [SandboxConfig]).
 * - **Arranque/parada**: [start] y [stop] son serializados por un `Mutex`,
 *   por lo que un doble toque en la UI no lanza dos procesos.
 * - **Estados**: [state] expone `STOPPED/BOOTING/RUNNING/ERROR` como
 *   [StateFlow]; [lastError] conserva el motivo del último `ERROR`.
 *
 * El proceso se sondea con `delay` (no con `waitFor` bloqueante) sobre el
 * scope inyectado, de modo que la máquina de estados es determinista y
 * testeable en tiempo virtual.
 *
 * @param config montaje y temporizaciones.
 * @param launcher fábrica del proceso; en producción [ProcessBuilderLauncher],
 *   en pruebas un doble. B4 lo sustituirá por una variante con PTY.
 * @param externalScope scope del supervisor de proceso. Si es `null` se crea
 *   uno propio (`SupervisorJob` + `Dispatchers.Default`) que vive mientras
 *   viva la app.
 */
class SandboxManager(
    private val config: SandboxConfig,
    private val launcher: SandboxProcessLauncher = ProcessBuilderLauncher(),
    externalScope: CoroutineScope? = null,
) {
    private val scope: CoroutineScope =
        externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SandboxState.STOPPED)
    val state: StateFlow<SandboxState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var process: SandboxProcessHandle? = null
    private var supervisor: Job? = null

    /** `true` cuando una [stop] está en curso (anula cualquier ERROR posterior). */
    @Volatile
    private var stopping = false

    val isRunning: Boolean
        get() = _state.value == SandboxState.RUNNING

    /** argv completo que se pasará a proot (útil para UI/diagnóstico). */
    fun command(): List<String> = config.buildCommand()

    /**
     * Arranca el sandbox. Devuelve el estado resultante.
     *
     * - Ya `RUNNING`/`BOOTING` → devuelto sin relanzar (idempotente).
     * - Configuración inválida → `ERROR` **sin** intentar lanzar nada.
     * - Proceso que sale dentro de la ventana de gracia → `ERROR` con el
     *   código de salida y la cola de stderr.
     * - Superada la gracia → `RUNNING` y arranca el supervisor de vida.
     */
    suspend fun start(): SandboxState = mutex.withLock {
        val current = _state.value
        if (current == SandboxState.RUNNING || current == SandboxState.BOOTING) {
            return@withLock current
        }
        config.validationError()?.let { reason -> return@withLock fail(reason, null) }

        _lastError.value = null
        _state.value = SandboxState.BOOTING

        val handle = try {
            writeDnsConfig()
            launcher.launch(config.buildCommand(), config.hostEnvironment(), config.rootfsDir)
        } catch (error: Exception) {
            return@withLock fail(startupFailureMessage(error), null)
        }

        process = handle
        stopping = false
        supervisor = scope.launch { supervise(handle) }

        var waited = 0L
        while (waited < config.bootGraceMs) {
            val exitCode = handle.pollExitCode()
            if (exitCode != null) {
                return@withLock fail(earlyExitMessage(exitCode, handle), handle)
            }
            delay(config.pollIntervalMs)
            waited += config.pollIntervalMs
        }
        _state.value = SandboxState.RUNNING
        SandboxState.RUNNING
    }

    /**
     * Detiene el sandbox: cancela el supervisor, envía SIGTERM (y SIGKILL
     * si el proceso no cede en `stopGraceMs`) y queda en `STOPPED` sin
     * error pendiente. Idempotente en cualquier estado.
     */
    suspend fun stop(): SandboxState = mutex.withLock {
        stopping = true
        try {
            supervisor?.cancelAndJoin()
            supervisor = null
            process?.let { handle ->
                withContext(Dispatchers.IO) { handle.destroy(config.stopGraceMs) }
            }
        } finally {
            process = null
            _lastError.value = null
            _state.value = SandboxState.STOPPED
            stopping = false
        }
        SandboxState.STOPPED
    }

    /**
     * Vuelve `ERROR` y limpia el proceso. Debe llamarse con el mutex tomado.
     * El supervisor se cancela sin unirse: mientras el estado sea `BOOTING`
     * su bucle jamás escribe estados, así que no hay carrera.
     */
    private suspend fun fail(reason: String, handle: SandboxProcessHandle?): SandboxState {
        supervisor?.cancel()
        supervisor = null
        if (handle != null) {
            withContext(Dispatchers.IO) { handle.destroy(config.stopGraceMs) }
        }
        process = null
        _lastError.value = reason
        _state.value = SandboxState.ERROR
        return SandboxState.ERROR
    }

    /**
     * Vigila la vida del proceso mientras `RUNNING`:
     * - salida con código 0 → `STOPPED` limpio (el usuario hizo `exit`);
     * - salida con código distinto de 0 → `ERROR` con detalle de stderr;
     * - salida durante `BOOTING` o durante una `stop` → la decide quien
     *   corresponda (ventana de gracia / stop), no el supervisor.
     */
    private suspend fun supervise(handle: SandboxProcessHandle) {
        while (true) {
            delay(config.pollIntervalMs)
            val exitCode = handle.pollExitCode() ?: continue
            if (stopping || _state.value != SandboxState.RUNNING) return
            if (exitCode == 0) {
                _lastError.value = null
                _state.value = SandboxState.STOPPED
            } else {
                _lastError.value = unexpectedExitMessage(exitCode, handle)
                _state.value = SandboxState.ERROR
            }
            return
        }
    }

    /**
     * Vuelca los DNS del dispositivo en `<rootfs>/etc/resolv.conf` si
     * `dnsServers` no está vacío (bionic resuelve vía netd y no publica un
     * `/etc/resolv.conf` que el guest pueda leer). No-op si no hay DNS o si
     * el archivo ya coincide: evita reescribir el rootfs en cada arranque.
     */
    private fun writeDnsConfig() {
        val content = config.resolvConfContent() ?: return
        val file = config.resolvConfFile()
        if (file.isFile && file.readText(Charsets.UTF_8) == content) return
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("no se pudo crear el directorio ${parent.path}")
        }
        file.writeText(content, Charsets.UTF_8)
    }

    private fun earlyExitMessage(exitCode: Int, handle: SandboxProcessHandle): String =
        "proot terminó durante el arranque (código $exitCode)${detailSuffix(handle)}"

    private fun unexpectedExitMessage(exitCode: Int, handle: SandboxProcessHandle): String =
        "el sandbox terminó inesperadamente (código $exitCode)${detailSuffix(handle)}"

    private fun startupFailureMessage(error: Exception): String =
        "no se pudo iniciar el sandbox: ${error.message ?: error.javaClass.simpleName}"

    private fun detailSuffix(handle: SandboxProcessHandle): String {
        val detail = handle.stderrTail(ERROR_DETAIL_CHARS).trim()
        return if (detail.isEmpty()) "" else " — $detail"
    }

    private companion object {
        const val ERROR_DETAIL_CHARS = 400
    }
}
