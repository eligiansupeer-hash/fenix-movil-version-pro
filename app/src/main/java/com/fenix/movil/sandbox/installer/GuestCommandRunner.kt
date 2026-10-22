// ============================================================================
// GuestCommandRunner.kt — ejecución de comandos batch en el guest (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.fenix.movil.sandbox.ProcessBuilderLauncher
import com.fenix.movil.sandbox.SandboxConfig
import com.fenix.movil.sandbox.SandboxProcessHandle
import com.fenix.movil.sandbox.SandboxProcessLauncher
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Resultado de un comando guest.
 *
 * @param exitCode código de salida; `null` si el proceso no terminó (timeout).
 * @param stdout cola del stdout (UTF-8), recortada al búfer del lanzador.
 * @param stderr cola del stderr (UTF-8), recortada al búfer del lanzador.
 * @param timedOut `true` si el comando exidió [GuestCommandRunner.run]'s
 *   `timeoutMs` y fue destruido.
 */
data class GuestResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    /** Éxito estricto: terminó (sin timeout) con código 0. */
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0
}

/**
 * Puerto para ejecutar un comando **no interactivo** dentro del guest
 * (instalación de CLIs, verificación de versiones).
 *
 * Separado de `SandboxProcessHandle` (B3) y de `PtyTransport` (B4): el
 * instalador necesita un comando efímero con acuse, no una sesión viva.
 */
interface GuestCommandRunner {
    /**
     * Ejecuta [command] en el guest y espera hasta [timeoutMs].
     *
     * @throws IOException si el proceso (proot) no arranca.
     * @throws IllegalStateException si la configuración del sandbox es
     *   inválida (se valida antes de lanzar nada).
     */
    suspend fun run(command: List<String>, timeoutMs: Long): GuestResult
}

/**
 * Implementación real: lanza **proot** con el comando pedido como
 * `guestCommand`, reutilizando la maquinaria de B3 (entorno host
 * reemplazado, entorno guest aislado vía `env -i`, binds por defecto).
 *
 * Cada [run] es un proceso proot **efímero e independiente** de la sesión
 * interactiva (B4): instalar CLIs no exige que la terminal esté abierta,
 * y dos runs nunca comparten estado (el plan los serializa con un Mutex).
 *
 * El sondeo de vida usa `delay` (tiempo virtual en pruebas, sin bloquear
 * hilos en producción), fiel al patrón de [com.fenix.movil.sandbox.SandboxManager].
 */
class ProotGuestCommandRunner(
    private val config: SandboxConfig,
    private val launcher: SandboxProcessLauncher = ProcessBuilderLauncher(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val captureChars: Int = ProcessBuilderLauncher.DEFAULT_CAPTURE_CHARS,
    private val stopGraceMs: Long = DEFAULT_STOP_GRACE_MS,
) : GuestCommandRunner {

    override suspend fun run(command: List<String>, timeoutMs: Long): GuestResult {
        require(command.isNotEmpty()) { "comando guest vacío" }
        require(timeoutMs > 0) { "timeoutMs debe ser > 0 (valor: $timeoutMs)" }
        require(pollIntervalMs > 0) { "pollIntervalMs debe ser > 0 (valor: $pollIntervalMs)" }
        config.validationError()?.let { reason ->
            throw IllegalStateException("sandbox no ejecutable: $reason")
        }

        return withContext(Dispatchers.IO) {
            val runConfig = config.copy(guestCommand = command, killOnExit = true)
            val handle = launcher.launch(
                runConfig.buildCommand(),
                runConfig.hostEnvironment(),
                runConfig.rootfsDir,
            )
            awaitExit(handle, timeoutMs)
        }
    }

    /** Sondea el proceso hasta la salida o el timeout; destruye en el timeout. */
    private suspend fun awaitExit(handle: SandboxProcessHandle, timeoutMs: Long): GuestResult {
        var waited = 0L
        while (waited < timeoutMs) {
            val exitCode = handle.pollExitCode()
            if (exitCode != null) {
                // El proceso salió, pero sus hilos de captura drenan el tubo
                // en background: una espera breve evita leer colas a medias.
                delay(pollIntervalMs)
                return GuestResult(
                    exitCode = exitCode,
                    stdout = handle.stdoutTail(captureChars),
                    stderr = handle.stderrTail(captureChars),
                    timedOut = false,
                )
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        handle.destroy(stopGraceMs)
        return GuestResult(
            exitCode = null,
            stdout = handle.stdoutTail(captureChars),
            stderr = handle.stderrTail(captureChars),
            timedOut = true,
        )
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 50L
        const val DEFAULT_STOP_GRACE_MS = 2_000L
    }
}
