// ============================================================================
// ShellSessionTest.kt — verificación de B4 (gate: test unitario ShellSession)
//
// Cobertura: ciclo de vida OPEN/CLOSED (apertura única, cierre idempotente,
// EOF del proceso), stdin (bytes crudos, terminador \r en PTY y \n en
// tubería), escape codes ANSI segmentados incluso entre dos lecturas,
// UTF-8 incremental (caracteres partidos y bytes malformados), scrollback
// acotado por stream, emisión sin consumidores y redimensión con acuse.
// ============================================================================

package com.fenix.movil.terminal

import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellSessionTest {

    // ------------------------------------------------------------------
    // Dobles
    // ------------------------------------------------------------------

    /** [PtyTransport] controlado por el test: sin procesos reales. */
    private class FakePtyTransport(
        override val isTty: Boolean = true,
        withStderr: Boolean = true,
    ) : PtyTransport {
        val input = RecordingOutputStream()
        val stdoutFeeder = FeedableInputStream()
        val stderrFeeder = FeedableInputStream()
        val resizes = CopyOnWriteArrayList<TerminalSize>()
        val closes = CopyOnWriteArrayList<Boolean>()

        @Volatile
        var resizeResult = true

        override val stdin: OutputStream
            get() = input

        override val stdout: InputStream = stdoutFeeder

        override val stderr: InputStream? = if (withStderr) stderrFeeder else null

        override fun resize(size: TerminalSize): Boolean {
            resizes.add(size)
            return resizeResult
        }

        override fun close(terminate: Boolean) {
            closes.add(terminate)
            // Un proceso que muere cierra sus tuberías: los lectores ven
            // EOF y terminan (el mismo contrato del transporte real).
            stdoutFeeder.endOfStream()
            stderrFeeder.endOfStream()
            input.close()
        }
    }

    /**
     * Suscriptor de [ShellSession.output] con `Dispatchers.Unconfined`:
     * la suscripción se completa de forma síncrona al construirlo (no hay
     * ventana en la que se pierdan emisiones) y cada emisión se procesa en
     * el hilo que emite. Se cancela en el `finally` de cada test.
     */
    private class ChunkRecorder(session: ShellSession) {
        val chunks = CopyOnWriteArrayList<TerminalChunk>()
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            session.output.collect { chunks.add(it) }
        }

        fun textOf(stream: TerminalStream): String = chunks
            .filter { it.stream == stream && it.kind == TerminalChunkKind.TEXT }
            .joinToString("") { it.text }

        fun escapesOf(stream: TerminalStream): List<String> = chunks
            .filter { it.stream == stream && it.kind == TerminalChunkKind.ESCAPE }
            .map { it.text }

        fun cancel() = job.cancel()
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    @Test
    fun `el estado inicial es CLOSED con la geometria por defecto`() {
        val session = ShellSession(FakePtyTransport())
        assertThat(session.state.value).isEqualTo(ShellSessionState.CLOSED)
        assertThat(session.size.value).isEqualTo(TerminalSize.DEFAULT)
    }

    @Test
    fun `operaciones antes de open fallan con IllegalStateException`() {
        val session = ShellSession(FakePtyTransport())
        assertThrows(IllegalStateException::class.java) { session.writeText("x") }
        assertThrows(IllegalStateException::class.java) { session.sendLine("x") }
        assertThrows(IllegalStateException::class.java) { session.resize(30, 100) }
        assertThat(session.state.value).isEqualTo(ShellSessionState.CLOSED)
    }

    @Test
    fun `open pasa a OPEN y una segunda apertura falla`() {
        val session = ShellSession(FakePtyTransport())
        session.open()
        assertThat(session.state.value).isEqualTo(ShellSessionState.OPEN)
        assertThrows(IllegalStateException::class.java) { session.open() }
        session.close()
    }

    @Test
    fun `las sesiones son de un solo uso`() {
        val session = ShellSession(FakePtyTransport())
        session.open()
        session.close()
        assertThrows(IllegalStateException::class.java) { session.open() }
    }

    @Test
    fun `close es idempotente y cierra el transporte una sola vez con terminate`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        session.close()
        session.close()
        session.close()
        assertThat(transport.closes).containsExactly(true)
        assertThat(session.state.value).isEqualTo(ShellSessionState.CLOSED)
        assertThrows(IllegalStateException::class.java) { session.writeText("x") }
        assertThrows(IllegalStateException::class.java) { session.resize(10, 10) }
    }

    @Test
    fun `el eof del proceso cierra la sesion sin terminate`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        transport.stdoutFeeder.feed("adios\n")
        waitUntil(what = "scrollback de stdout") { session.plainText().contains("adios") }
        transport.stdoutFeeder.endOfStream()
        waitUntil(what = "estado CLOSED por EOF") {
            session.state.value == ShellSessionState.CLOSED
        }
        // El cierre lo originó el proceso: no se termina a medias.
        assertThat(transport.closes).containsExactly(false)
        assertThat(transport.input.isClosed).isTrue()
    }

    // ------------------------------------------------------------------
    // stdin
    // ------------------------------------------------------------------

    @Test
    fun `write envia bytes crudos y texto UTF-8 por stdin`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        session.write(byteArrayOf(0x1B, 0x5B, 0x41)) // secuencia cruda
        session.writeText("áé")
        assertThat(transport.input.text()).isEqualTo("\u001B[Aáé")
        session.close()
    }

    @Test
    fun `sendLine termina con CR en PTY`() {
        val transport = FakePtyTransport(isTty = true)
        val session = ShellSession(transport)
        session.open()
        session.sendLine("ls -la")
        assertThat(transport.input.text()).isEqualTo("ls -la\r")
        session.close()
    }

    @Test
    fun `sendLine termina con LF sin PTY`() {
        val transport = FakePtyTransport(isTty = false)
        val session = ShellSession(transport)
        session.open()
        session.sendLine("ls -la")
        assertThat(transport.input.text()).isEqualTo("ls -la\n")
        session.close()
    }

    // ------------------------------------------------------------------
    // Escape codes, UTF-8 y scrollback
    // ------------------------------------------------------------------

    @Test
    fun `stdout llega como chunks TEXT y al scrollback`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        val recorder = ChunkRecorder(session)
        try {
            session.open()
            transport.stdoutFeeder.feed("hola终端\n")
            waitUntil(what = "scrollback de stdout") {
                session.plainText().contains("hola终端")
            }
            waitUntil(what = "chunk TEXT de stdout") {
                recorder.textOf(TerminalStream.STDOUT).contains("hola终端")
            }
            assertThat(recorder.escapesOf(TerminalStream.STDOUT)).isEmpty()
            assertThat(session.plainText(TerminalStream.STDERR)).isEmpty()
        } finally {
            recorder.cancel()
            session.close()
        }
    }

    @Test
    fun `una secuencia ANSI partida entre dos lecturas se emite una sola vez`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        val recorder = ChunkRecorder(session)
        try {
            session.open()
            transport.stdoutFeeder.feed("\u001B[3")
            // Garantiza que el primer trozo se leyó en un read() propio:
            // el segundo llega en una llamada de decode posterior.
            waitUntil(what = "primer trozo leido") { transport.stdoutFeeder.taken >= 3 }
            transport.stdoutFeeder.feed("1mrojo\u001B[0m\n")
            waitUntil(what = "scrollback libre de escapes") {
                session.plainText() == "rojo\n"
            }
            waitUntil(what = "dos secuencias segmentadas") {
                recorder.escapesOf(TerminalStream.STDOUT).size == 2
            }
            assertThat(recorder.escapesOf(TerminalStream.STDOUT))
                .containsExactly("\u001B[31m", "\u001B[0m")
                .inOrder()
            assertThat(session.plainText()).isEqualTo("rojo\n")
        } finally {
            recorder.cancel()
            session.close()
        }
    }

    @Test
    fun `un caracter UTF-8 partido entre dos lecturas se recompone`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        val bytes = "á€".toByteArray(Charsets.UTF_8) // C3 A1 | E2 82 AC
        val cut = 4 // corta a mitad del € (queda solo un byte de continuación)
        transport.stdoutFeeder.feed(bytes.copyOfRange(0, cut))
        waitUntil(what = "primer trozo leido") { transport.stdoutFeeder.taken >= cut }
        transport.stdoutFeeder.feed(bytes.copyOfRange(cut, bytes.size))
        waitUntil(what = "caracteres recomuestos") { session.plainText() == "á€" }
        session.close()
    }

    @Test
    fun `un emoji de cuatro bytes sobrevive al corte`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        val bytes = "😀→".toByteArray(Charsets.UTF_8)
        val cut = 3
        transport.stdoutFeeder.feed(bytes.copyOfRange(0, cut))
        waitUntil(what = "primer trozo leido") { transport.stdoutFeeder.taken >= cut }
        transport.stdoutFeeder.feed(bytes.copyOfRange(cut, bytes.size))
        waitUntil(what = "emoji recomuesto") { session.plainText() == "😀→" }
        session.close()
    }

    @Test
    fun `bytes malformados producen U+FFFD sin romper la linea`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        transport.stdoutFeeder.feed(byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        waitUntil(what = "texto procesado") { session.plainText().contains("B") }
        assertThat(session.plainText()).isEqualTo("A\uFFFDB")
        session.close()
    }

    @Test
    fun `el scrollback se acota al tope configurado`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport, scrollbackChars = 8)
        session.open()
        transport.stdoutFeeder.feed("0123456789abcde")
        waitUntil(what = "scrollback acotado") { session.plainText().length == 8 }
        assertThat(session.plainText()).isEqualTo("789abcde")
        session.close()
    }

    @Test
    fun `la sesion no se bloquea aunque nadie consuma los chunks`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open() // sin suscriptor: los chunks se descartan, el texto no
        (1..500).forEach { transport.stdoutFeeder.feed("linea-$it\n") }
        waitUntil(what = "scrollback completo") {
            session.plainText().contains("linea-500")
        }
        assertThat(session.plainText()).contains("linea-1")
        session.close()
    }

    // ------------------------------------------------------------------
    // stderr
    // ------------------------------------------------------------------

    @Test
    fun `stderr del transporte se segmenta en su stream propio`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        val recorder = ChunkRecorder(session)
        try {
            session.open()
            transport.stderrFeeder.feed("aviso del puente\n")
            waitUntil(what = "scrollback de stderr") {
                session.plainText(TerminalStream.STDERR).contains("aviso del puente")
            }
            waitUntil(what = "chunk de stderr") {
                recorder.textOf(TerminalStream.STDERR).contains("aviso")
            }
            assertThat(session.plainText()).isEmpty()
            assertThat(recorder.textOf(TerminalStream.STDOUT)).isEmpty()
        } finally {
            recorder.cancel()
            session.close()
        }
    }

    @Test
    fun `un transporte sin stderr no inicia su lector`() {
        val transport = FakePtyTransport(withStderr = false)
        val session = ShellSession(transport)
        session.open()
        transport.stdoutFeeder.feed("ok\n")
        waitUntil(what = "salida de stdout") { session.plainText().contains("ok") }
        assertThat(session.plainText(TerminalStream.STDERR)).isEmpty()
        session.close()
    }

    // ------------------------------------------------------------------
    // Redimensión
    // ------------------------------------------------------------------

    @Test
    fun `resize propaga la ventana al transporte y actualiza size`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        assertThat(session.resize(30, 100)).isTrue()
        assertThat(session.size.value).isEqualTo(TerminalSize(30, 100))
        assertThat(transport.resizes).containsExactly(TerminalSize(30, 100))
        // Sin acuse del guest la llamada devuelve false pero se propaga.
        transport.resizeResult = false
        assertThat(session.resize(TerminalSize(40, 120))).isFalse()
        assertThat(transport.resizes).hasSize(2)
        assertThat(session.size.value).isEqualTo(TerminalSize(40, 120))
        session.close()
    }

    @Test
    fun `resize fuera de rango falla sin tocar el transporte`() {
        val transport = FakePtyTransport()
        val session = ShellSession(transport)
        session.open()
        assertThrows(IllegalArgumentException::class.java) { session.resize(0, 80) }
        assertThrows(IllegalArgumentException::class.java) { session.resize(24, 1_000) }
        assertThrows(IllegalArgumentException::class.java) { session.resize(-5, 80) }
        assertThat(transport.resizes).isEmpty()
        assertThat(session.size.value).isEqualTo(TerminalSize.DEFAULT)
        session.close()
    }
}
