// ============================================================================
// ShellSession.kt — sesión de shell interactiva (plan B4)
// ============================================================================

package com.fenix.movil.terminal

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estados de una [ShellSession]. */
enum class ShellSessionState {
    /** Sin sesión: antes de [ShellSession.open] o después de cerrar. */
    CLOSED,

    /** Sesión abierta: lectores activos, stdin operativo. */
    OPEN,
}

/**
 * Sesión de shell: el lado de aplicación de la PTY (plan B4).
 *
 * Responsabilidades (las 5 del enunciado):
 * 1. **PTY** — delega en un [PtyTransport]; en producción el puente
 *    `BridgePtyTransport` (sobre proot + la PTY que vive en el guest).
 * 2. **Streams stdin/stdout/stderr** — [write]/[writeText]/[sendLine]
 *    sobre stdin; stdout/stderr llegan como [TerminalChunk] por [output]
 *    y como texto plano por [plainText].
 * 3. **Escape codes** — [AnsiEscapeScanner] con estado separa texto
 *    literal de secuencias ANSI incluso partidas entre dos lecturas; las
 *    secuencias se conservan crudas para la vista VT (D3) y no contaminan
 *    el scrollback.
 * 4. **Codificación UTF-8** — [Utf8StreamDecoder] incremental: un carácter
 *    partido entre dos `read()` se recompone; los bytes malformados
 *    producen `U+FFFD`.
 * 5. **Redimensión (rows/cols)** — [resize] valida la geometría, la expone
 *    en [size] (la vista responde ya) y la propaga al transporte con
 *    acuse (el guest aplica `stty` → `TIOCSWINSZ` → `SIGWINCH`).
 *
 * Modelo de concurrencia: dos hilos daemon de lectura (uno por stream)
 * emiten a [output] (buffer acotado con descarte de los más antiguos: la
 * memoria nunca crece sin límite aunque nadie consuma) y mantienen un
 * scrollback de texto plano acotado por stream ([plainText]), que es lo
 * que consume `SandboxProcessHandle.stdoutTail/stderrTail` y lo que usa
 * una vista que se suscribe tarde.
 *
 * [open] es de un solo uso (el transporte subyacente también lo es).
 */
class ShellSession(
    private val transport: PtyTransport,
    initialSize: TerminalSize = TerminalSize.DEFAULT,
    private val scrollbackChars: Int = DEFAULT_SCROLLBACK_CHARS,
) {
    private val lock = Any()
    private val scrollLock = Any()
    private var started = false
    private var closed = false
    private val readers = mutableMapOf<TerminalStream, Thread>()

    private val _state = MutableStateFlow(ShellSessionState.CLOSED)
    val state: StateFlow<ShellSessionState> = _state.asStateFlow()

    private val _size = MutableStateFlow(initialSize)
    val size: StateFlow<TerminalSize> = _size.asStateFlow()

    private val _output = MutableSharedFlow<TerminalChunk>(
        extraBufferCapacity = OUTPUT_BUFFER_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Chunks en vivo (texto y escapes) en orden de llegada, por stream. */
    val output: SharedFlow<TerminalChunk> = _output.asSharedFlow()

    private val scrollbacks: Map<TerminalStream, StringBuilder> =
        TerminalStream.entries.associateWith { StringBuilder() }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Abre la sesión y arranca los lectores de stdout/stderr.
     * Debe llamarse una sola vez, antes de usar [write]/[resize].
     */
    fun open() {
        synchronized(lock) {
            check(!started && !closed) {
                if (closed) "ShellSession cerrada: las sesiones son de un solo uso"
                else "ShellSession ya abierta"
            }
            started = true
            _state.value = ShellSessionState.OPEN
            startReader(TerminalStream.STDOUT, transport.stdout)
            transport.stderr?.let { startReader(TerminalStream.STDERR, it) }
        }
    }

    /**
     * Cierra la sesión de forma deliberada: avisa al canal de control,
     * termina al proceso hijo y espera a los lectores. Idempotente.
     */
    fun close() = closeSession(terminate = true)

    // ------------------------------------------------------------------
    // stdin
    // ------------------------------------------------------------------

    /** Escribe bytes crudos en stdin (p. ej. teclas o secuencias de teclado). */
    fun write(bytes: ByteArray) {
        ensureOpen()
        synchronized(stdinLock) {
            transport.stdin.write(bytes)
            transport.stdin.flush()
        }
    }

    /** Escribe [text] codificado en UTF-8. */
    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * Escribe una línea terminada correctamente para el transporte:
     * `\r` con PTY (la disciplina de línea la convierte en `\n` vía
     * ICRNL) y `\n` con tuberías crudas.
     */
    fun sendLine(text: String) {
        val terminator = if (transport.isTty) "\r" else "\n"
        write((text + terminator).toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------
    // Redimensión
    // ------------------------------------------------------------------

    /**
     * Redimensiona la sesión. La geometría nueva queda visible en [size]
     * **inmediatamente** (la vista VT debe replegarse ya) y se propaga al
     * guest; devuelve `true` si el tty del guest la aplicó (acuse del
     * canal de control) y `false` si no había acuse — la llamada es
     * reintentable.
     *
     * @throws IllegalArgumentException si la geometría está fuera de
     *   rango (no se toca el transporte).
     */
    fun resize(newSize: TerminalSize): Boolean {
        ensureOpen()
        _size.value = newSize
        return transport.resize(newSize)
    }

    /** Atajo [resize] con validación en `TerminalSize`. */
    fun resize(rows: Int, cols: Int): Boolean = resize(TerminalSize(rows, cols))

    // ------------------------------------------------------------------
    // stdout / stderr proyectados
    // ------------------------------------------------------------------

    /**
     * Scrollback de texto plano del stream (secuencias de escape ya
     * eliminadas), acotado a [scrollbackChars]. Seguro desde cualquier
     * hilo y usable con la sesión cerrada.
     */
    fun plainText(stream: TerminalStream = TerminalStream.STDOUT): String =
        synchronized(scrollLock) { scrollbacks.getValue(stream).toString() }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private val stdinLock = Any()

    private fun ensureOpen() {
        synchronized(lock) {
            check(started && !closed) {
                when {
                    closed -> "ShellSession cerrada"
                    else -> "ShellSession no abierta: llamar a open()"
                }
            }
        }
    }

    private fun startReader(stream: TerminalStream, input: InputStream) {
        val thread = Thread({ readLoop(stream, input) }, "fenix-shell-$stream").apply {
            isDaemon = true
            start()
        }
        readers[stream] = thread
    }

    private fun readLoop(stream: TerminalStream, input: InputStream) {
        val decoder = Utf8StreamDecoder()
        val scanner = AnsiEscapeScanner()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val piece = decoder.decode(buffer, read)
                if (piece.isNotEmpty()) dispatch(stream, scanner.feed(piece))
            }
        } catch (expected: IOException) {
            // El transporte cerró el stream: fin normal del lector.
        } finally {
            val residual = decoder.flush()
            if (residual.isNotEmpty()) dispatch(stream, scanner.feed(residual))
            scanner.flush()?.let { dispatch(stream, listOf(it)) }
            if (stream == TerminalStream.STDOUT) {
                // El proceso cerró stdout: la sesión terminó por sí sola.
                closeSession(terminate = false)
            }
        }
    }

    private fun dispatch(stream: TerminalStream, tokens: List<AnsiToken>) {
        tokens.forEach { token ->
            val isText = token.kind == AnsiTokenKind.TEXT
            val kind = if (isText) TerminalChunkKind.TEXT else TerminalChunkKind.ESCAPE
            if (isText) appendPlain(stream, token.text)
            _output.tryEmit(TerminalChunk(stream, kind, token.text))
        }
    }

    private fun appendPlain(stream: TerminalStream, piece: String) {
        if (piece.isEmpty()) return
        synchronized(scrollLock) {
            val scrollback = scrollbacks.getValue(stream)
            scrollback.append(piece)
            if (scrollback.length > scrollbackChars) {
                scrollback.delete(0, scrollback.length - scrollbackChars)
            }
        }
    }

    private fun closeSession(terminate: Boolean) {
        val firstCaller = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!firstCaller) return
        _state.value = ShellSessionState.CLOSED
        runCatching { transport.close(terminate) }
        val current = Thread.currentThread()
        readers.values.forEach { reader ->
            if (reader !== current) {
                runCatching { reader.join(READER_JOIN_TIMEOUT_MS) }
            }
        }
    }

    private companion object {
        const val DEFAULT_SCROLLBACK_CHARS = 64_000
        const val READ_BUFFER_BYTES = 4_096
        const val OUTPUT_BUFFER_CHUNKS = 1_024
        const val READER_JOIN_TIMEOUT_MS = 2_000L
    }
}
