// ============================================================================
// SandboxProcess.kt — abstracción de proceso proot + lanzador real
// ============================================================================

package com.fenix.movil.sandbox

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Manejador del proceso proot ya lanzado.
 *
 * La interfaz es deliberadamente mínima y sin dependencias de Android para
 * que B4 (`ShellSession`) pueda sustituir el lanzador por una implementación
 * con PTY sin tocar la máquina de estados de [SandboxManager].
 */
interface SandboxProcessHandle {
    val isAlive: Boolean

    /** Código de salida si terminó; `null` mientras siga corriendo. */
    fun pollExitCode(): Int?

    /**
     * SIGTERM al proceso y, si [graceMillis] ms después sigue vivo, SIGKILL.
     * Puede bloquear ese lapso: invocar desde un dispatcher de IO.
     */
    fun destroy(graceMillis: Long)

    /** Últimos [maxChars] del stdout capturado (UTF-8). */
    fun stdoutTail(maxChars: Int): String

    /** Últimos [maxChars] del stderr capturado (UTF-8). */
    fun stderrTail(maxChars: Int): String
}

/** Fábrica de [SandboxProcessHandle] a partir de un argv ya construido. */
interface SandboxProcessLauncher {
    fun launch(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): SandboxProcessHandle
}

/**
 * Lanzador real basado en `ProcessBuilder`: funciona en Android (ejecuta el
 * proot extraído en `nativeLibraryDir`, directorio de sólo lectura con
 * permiso de ejecución) y en la JVM de desarrollo/pruebas.
 *
 * Dos decisiones deliberadas:
 * 1. **Entorno reemplazado** (no heredado): `environment().clear()` + los
 *    pares recibidos. Nada del entorno de la app llega al proceso hijo.
 * 2. **stdout y stderr consumidos** en hilos daemon con búfer acotado: si
 *    nadie los leyera, la tubería (64 KB) se llenaría y bloquearía al
 *    sandbox en cuanto escribiera unos kilobytes.
 */
class ProcessBuilderLauncher(
    private val captureChars: Int = DEFAULT_CAPTURE_CHARS,
) : SandboxProcessLauncher {
    override fun launch(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): SandboxProcessHandle {
        val builder = ProcessBuilder(command).directory(workingDir)
        val childEnvironment = builder.environment()
        childEnvironment.clear()
        childEnvironment.putAll(environment)
        val process = builder.start()
        return ProcessHandle(process, captureChars)
    }

    companion object {
        /** Búfer máximo retenido por stream (carácteres). */
        const val DEFAULT_CAPTURE_CHARS = 8_192
    }
}

/** Implementación de [SandboxProcessHandle] sobre `java.lang.Process`. */
internal class ProcessHandle(
    private val process: Process,
    captureChars: Int,
) : SandboxProcessHandle {
    private val stdout = OutputTail(process.inputStream, captureChars, "fenix-sandbox-stdout")
    private val stderr = OutputTail(process.errorStream, captureChars, "fenix-sandbox-stderr")

    override val isAlive: Boolean
        get() = process.isAlive

    override fun pollExitCode(): Int? = try {
        process.exitValue()
    } catch (expected: IllegalThreadStateException) {
        null
    }

    override fun destroy(graceMillis: Long) {
        if (!process.isAlive) return
        process.destroy()
        val grace = graceMillis.coerceAtLeast(0L)
        if (!process.waitFor(grace, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun stdoutTail(maxChars: Int): String = stdout.tail(maxChars)

    override fun stderrTail(maxChars: Int): String = stderr.tail(maxChars)

    private companion object {
        const val FORCE_KILL_WAIT_MS = 1_000L
    }
}

/**
 * Búfer circular simple (sufijo del stream) consumido por un hilo daemon.
 * `tail()` es seguro desde cualquier hilo; el hilo termina con EOF o con la
 * IOException que provoca cerrar el stream al destruir el proceso.
 */
internal class OutputTail(
    stream: InputStream,
    maxChars: Int,
    threadName: String,
) {
    private val limit = maxChars.coerceAtLeast(1)
    private val lock = Any()
    private val buffer = StringBuilder()

    init {
        Thread({ drain(stream) }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    private fun drain(stream: InputStream) {
        try {
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                val chunk = CharArray(CHUNK_CHARS)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    append(chunk, read)
                }
            }
        } catch (expected: IOException) {
            // Stream cerrado al destruir el proceso: fin normal del hilo.
        }
    }

    private fun append(chunk: CharArray, read: Int) {
        synchronized(lock) {
            buffer.append(chunk, 0, read)
            if (buffer.length > limit) {
                buffer.delete(0, buffer.length - limit)
            }
        }
    }

    fun tail(maxChars: Int): String {
        if (maxChars <= 0) return ""
        synchronized(lock) {
            val start = (buffer.length - maxChars.coerceAtMost(limit)).coerceAtLeast(0)
            return buffer.substring(start)
        }
    }

    private companion object {
        const val CHUNK_CHARS = 1_024
    }
}
