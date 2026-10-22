// ============================================================================
// BridgePtyTransport.kt — puente host ↔ PTY del guest (plan B4)
// ============================================================================

package com.fenix.movil.sandbox

import com.fenix.movil.terminal.PtyTransport
import com.fenix.movil.terminal.TerminalSize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proceso de sesión PTY en el lado host: los tres streams crudos más los
 * controles de vida que [BridgePtyTransport] necesita.
 *
 * No se reutiliza [SandboxProcessHandle] (B3) porque esa interfaz es
 * deliberadamente **sin** streams crudos: sus hilos [OutputTail] consumen
 * stdout/stderr para el volcado de errores de arranque. Una sesión
 * interactiva necesita el stream en vivo, así que este puerto es aparte.
 */
interface PtyProcess {
    /** stdin del proceso (hacia el pty vía `script(1)`). */
    val stdin: OutputStream

    /** stdout del proceso (desde el pty: incluye el stderr del guest). */
    val stdout: InputStream

    /** stderr del proceso (diagnósticos de proot/puente, fuera del pty). */
    val stderr: InputStream

    fun isAlive(): Boolean

    /** SIGTERM y, si en [graceMillis] ms sigue vivo, SIGKILL. */
    fun destroy(graceMillis: Long)
}

/** Fábrica de [PtyProcess] a partir del argv de la sesión PTY. */
fun interface PtyProcessLauncher {
    fun launch(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): PtyProcess
}

/** [PtyProcessLauncher] real sobre `ProcessBuilder` (entorno reemplazado). */
class ProcessBuilderPtyLauncher : PtyProcessLauncher {
    override fun launch(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File,
    ): PtyProcess {
        val builder = ProcessBuilder(command).directory(workingDir)
        val childEnvironment = builder.environment()
        childEnvironment.clear()
        childEnvironment.putAll(environment)
        return ProcessPtyProcess(builder.start())
    }
}

/** [PtyProcess] sobre `java.lang.Process` (proceso proot + helper PTY). */
class ProcessPtyProcess(private val process: Process) : PtyProcess {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    override fun isAlive(): Boolean = process.isAlive

    override fun destroy(graceMillis: Long) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(graceMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val FORCE_KILL_WAIT_MS = 1_000L
    }
}

/**
 * Puente entre [com.fenix.movil.terminal.ShellSession] y la PTY que vive
 * **dentro** del sandbox (plan B4).
 *
 * La PTY no se crea en el host (bionic no expone `openpty` de forma
 * portable): se materializa en el guest con las herramientas del rootfs
 * Debian 12 (B2) vía [PtyHelper] — `script(1)` abre el pty y ejecuta el
 * shell colgado de él; `stty(1)` aplica la ventana (`TIOCSWINSZ` →
 * `SIGWINCH`). El host solo ve tuberías (stdin/stdout/stderr del proceso
 * proot) y el canal de control del directorio compartido (`/fenix`),
 * visible a ambos lados por el bind mount.
 *
 * Protocolo de redimensión (con acuse, no a ciegas):
 * 1. el host escribe `"filas columnas"` en el FIFO de control;
 * 2. el oyente del guest aplica `stty -F <pts> rows R cols C`;
 * 3. el guest acusa en el archivo de tamaño (`"filas columnas"`);
 * 4. [resize] espera ese acuse: `true` = ventana aplicada en el tty,
 *    `false` = sin acuse en el plazo (reintentable, contrato de
 *     [PtyTransport.resize]).
 *
 * Concurrencia: los writes del canal de control están serializados con un
 * lock (FIFO único); [close] es idempotente vía flag atómico.
 */
class BridgePtyTransport private constructor(
    private val process: PtyProcess,
    private val shareDir: File,
    private val ackTimeoutMs: Long,
    private val stopGraceMs: Long,
) : PtyTransport {

    private val controlLock = Any()
    private val closed = AtomicBoolean(false)

    /** Toda la salida del pty tiene semántica de tty real. */
    override val isTty: Boolean = true

    override val stdin: OutputStream get() = process.stdin
    override val stdout: InputStream get() = process.stdout
    override val stderr: InputStream get() = process.stderr

    /**
     * Escribe la ventana en el canal de control y espera el acuse del
     * guest. Devuelve `false` (sin destruir nada) si el proceso ya no
     * vive, si el canal no aceptó la escritura en [ackTimeoutMs] o si el
     * guest no acusó la geometría en ese plazo: la llamada es
     * reintentable, fiel al contrato de [PtyTransport.resize].
     */
    override fun resize(size: TerminalSize): Boolean {
        if (closed.get() || !process.isAlive()) return false
        if (!writeControl(size.toProtocol())) return false
        return awaitSizeAck(size, ackTimeoutMs)
    }

    /**
     * Cierra la sesión. Con [terminate] envía `QUIT` al oyente del guest
     * (limpia el FIFO y el archivo de acuse) y termina al proceso con la
     * gracia configurada; sin [terminate] (el proceso originó el cierre)
     * sólo se cierra stdin: tocar la salida a medias podría alterar su
     * código de salida.
     */
    override fun close(terminate: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (terminate) {
            writeControl(QUIT_TOKEN)
            runCatching { process.destroy(stopGraceMs) }
        } else {
            runCatching { process.stdin.close() }
        }
    }

    // ------------------------------------------------------------------
    // Canal de control
    // ------------------------------------------------------------------

    /**
     * Escribe [line] en el FIFO de control con tope de espera: abrir un
     * FIFO en escritura bloquea hasta que el oyente del guest lo abra en
     * lectura, y si el guest ya murió ese open quedaría colgado para
     * siempre. La escritura corre en un hilo daemon y [writeControl]
     * devuelve `false` si no completa en [ackTimeoutMs].
     */
    private fun writeControl(line: String): Boolean {
        synchronized(controlLock) {
            val fifo = PtyHelper.ctrlFile(shareDir)
            val done = CountDownLatch(1)
            Thread(
                {
                    try {
                        FileOutputStream(fifo, true).use { out ->
                            out.write((line + "\n").toByteArray(Charsets.UTF_8))
                        }
                    } catch (_: IOException) {
                        // FIFO inexistente o sin lector: el acuse decidirá.
                    } finally {
                        done.countDown()
                    }
                },
                "fenix-pty-ctrl",
            ).apply {
                isDaemon = true
                start()
            }
            return done.await(ackTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Espera a que el archivo de acuse del guest refleje [size]. */
    private fun awaitSizeAck(size: TerminalSize, timeoutMs: Long): Boolean {
        val ackFile = PtyHelper.sizeFile(shareDir)
        val expected = size.toProtocol()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (ackFile.isFile) {
                val actual = runCatching {
                    ackFile.readText(Charsets.UTF_8).trim()
                }.getOrNull()
                if (actual == expected) return true
            }
            if (!process.isAlive()) return false
            Thread.sleep(ACK_POLL_MS)
        }
        return false
    }

    companion object {
        /** Token de parada del oyente del guest (protocolo de PtyHelper). */
        const val QUIT_TOKEN = "QUIT"

        /** Ruta del directorio compartido vista **dentro** del guest. */
        const val GUEST_SHARE = "/fenix"

        private const val ACK_POLL_MS = 25L

        /**
         * Abre una sesión PTY para [config]:
         * 1. valida la configuración (igual que [SandboxManager.start], sin
         *    lanzar nada si es inválida);
         * 2. instala [PtyHelper] en el directorio compartido y arma la
         *    variante PTY del config: el comando guest pasa a ser el
         *    helper, el comando original viaja en `FENIX_SHELL` y la
         *    ventana inicial en `FENIX_ROWS`/`FENIX_COLS`;
         * 3. lanza proot vía [launcher];
         * 4. espera el **acuse de ventana inicial** del guest: sin él la
         *    sesión no es usable y se destruye el proceso.
         *
         * @param shareDir directorio host montado en `/fenix` (debe ser
         *   absoluto y estar creado o creable).
         * @throws IllegalArgumentException si [config] es inválida o
         *   [shareDir] no es absoluto.
         * @throws IOException si proot no arranca o el guest no acusa la
         *   ventana inicial en [ackTimeoutMs].
         */
        @Throws(IOException::class)
        fun open(
            config: SandboxConfig,
            shareDir: File,
            launcher: PtyProcessLauncher = ProcessBuilderPtyLauncher(),
            initialSize: TerminalSize = TerminalSize.DEFAULT,
            ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
            stopGraceMs: Long = config.stopGraceMs,
        ): BridgePtyTransport {
            config.validationError()?.let { reason ->
                throw IllegalArgumentException("config del sandbox inválida: $reason")
            }
            require(shareDir.isAbsolute) {
                "el directorio compartido debe ser absoluto: '${shareDir.path}'"
            }
            PtyHelper.install(shareDir)

            val ptyConfig = ptyConfig(config, shareDir, initialSize)
            val process = try {
                launcher.launch(
                    ptyConfig.buildCommand(),
                    ptyConfig.hostEnvironment(),
                    ptyConfig.rootfsDir,
                )
            } catch (error: IOException) {
                throw IOException(
                    "no se pudo lanzar la sesión PTY: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }

            val transport = BridgePtyTransport(process, shareDir, ackTimeoutMs, stopGraceMs)
            if (!transport.awaitSizeAck(initialSize, ackTimeoutMs)) {
                transport.close(terminate = true)
                throw IOException(
                    "la PTY del guest no confirmó la ventana inicial " +
                        "(${initialSize.toProtocol()}) en $ackTimeoutMs ms",
                )
            }
            return transport
        }

        /**
         * Une un proceso **ya lanzado** a un bridge (sobre un host real o
         * un doble de proceso) y espera el acuse de ventana inicial. Útil
         * para integrar el helper sin proot y para pruebas de protocolo.
         */
        @Throws(IOException::class)
        fun attach(
            process: PtyProcess,
            shareDir: File,
            initialSize: TerminalSize = TerminalSize.DEFAULT,
            ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
            stopGraceMs: Long = DEFAULT_STOP_GRACE_MS,
        ): BridgePtyTransport {
            require(shareDir.isAbsolute) {
                "el directorio compartido debe ser absoluto: '${shareDir.path}'"
            }
            val transport = BridgePtyTransport(process, shareDir, ackTimeoutMs, stopGraceMs)
            if (!transport.awaitSizeAck(initialSize, ackTimeoutMs)) {
                transport.close(terminate = true)
                throw IOException(
                    "la sesión no confirmó la ventana inicial " +
                        "(${initialSize.toProtocol()}) en $ackTimeoutMs ms",
                )
            }
            return transport
        }

        /**
         * Variante PTY de [config] **sin** tocar los binds: monta el
         * helper como comando guest.
         *
         * - [SandboxConfig.guestCommand] original → `FENIX_SHELL` (citado
         *   para `sh -c`); el helper lo re-executa dentro de la PTY.
         * - El comando guest pasa a ser el helper en `/fenix`.
         */
        fun ptyVariant(config: SandboxConfig, initialSize: TerminalSize): SandboxConfig =
            config.copy(
                guestCommand = listOf("/bin/sh", "$GUEST_SHARE/${PtyHelper.SCRIPT_NAME}"),
                guestEnv = config.guestEnv + mapOf(
                    "FENIX_SHARE" to GUEST_SHARE,
                    "FENIX_ROWS" to initialSize.rows.toString(),
                    "FENIX_COLS" to initialSize.cols.toString(),
                    "FENIX_SHELL" to shellJoin(config.guestCommand),
                ),
            )

        /**
         * [config] + variante PTY con el bind de [shareDir] en `/fenix`
         * (reemplaza cualquier bind previo del mismo guest path).
         */
        fun ptyConfig(
            config: SandboxConfig,
            shareDir: File,
            initialSize: TerminalSize,
        ): SandboxConfig = ptyVariant(config, initialSize).copy(
            binds = ensureShareBind(config.binds, shareDir),
        )

        private fun ensureShareBind(binds: List<BindMount>, shareDir: File): List<BindMount> {
            val withoutShare = binds.filter { it.guestPath != GUEST_SHARE }
            return withoutShare + BindMount(shareDir.absolutePath, GUEST_SHARE)
        }

        /**
         * Une un comando guest a una línea para `sh -c` dentro de la PTY.
         * Los tokens con caracteres seguros pasan tal cual (legibilidad en
         * `ps`); el resto va entre comillas simples.
         */
        fun shellJoin(command: List<String>): String =
            command.joinToString(" ") { token ->
                if (SAFE_TOKEN.matches(token)) token else singleQuote(token)
            }

        private fun singleQuote(token: String): String =
            "'" + token.replace("'", "'\\''") + "'"

        private val SAFE_TOKEN = Regex("[A-Za-z0-9_./=:@%+-]+")

        const val DEFAULT_ACK_TIMEOUT_MS = 5_000L
        const val DEFAULT_STOP_GRACE_MS = 2_000L
    }
}
