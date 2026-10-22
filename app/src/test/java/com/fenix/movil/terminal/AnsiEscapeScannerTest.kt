// ============================================================================
// AnsiEscapeScannerTest.kt — segmentación de secuencias ANSI (plan B4)
//
// Verifica que texto y escapes se separen con estado, incluso partidos
// entre dos `feed()`, que se cubre la gramática (CSI/OSC/DCS/designadores/
// ESC de 2 caracteres) y que el techo anti-memoria funciona.
// ============================================================================

package com.fenix.movil.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnsiEscapeScannerTest {
    private val scanner = AnsiEscapeScanner()

    /** Alimenta [chunks] y vuelca el pendiente (cierre de stream). */
    private fun feedAll(vararg chunks: String): List<AnsiToken> {
        val tokens = mutableListOf<AnsiToken>()
        chunks.forEach { tokens += scanner.feed(it) }
        scanner.flush()?.let { tokens += it }
        return tokens
    }

    @Test
    fun `el texto plano sale como un solo token TEXT`() {
        assertThat(feedAll("hola mundo\n"))
            .containsExactly(AnsiToken(AnsiTokenKind.TEXT, "hola mundo\n"))
    }

    @Test
    fun `un SGR completo se separa del texto circundante`() {
        val tokens = feedAll("antes ", "\u001B[31m", "rojo", "\u001B[0m", " despues")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.TEXT, "antes "),
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[31m"),
            AnsiToken(AnsiTokenKind.TEXT, "rojo"),
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[0m"),
            AnsiToken(AnsiTokenKind.TEXT, " despues"),
        ).inOrder()
    }

    @Test
    fun `una CSI partida entre dos feeds se completa una sola vez`() {
        val first = scanner.feed("x\u001B[3")
        val second = scanner.feed("1m")
        assertThat(first).containsExactly(AnsiToken(AnsiTokenKind.TEXT, "x"))
        assertThat(second).containsExactly(AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[31m"))
        // El texto que sigue al cierre de la secuencia se emite ya: el
        // scrollback debe actualizarse con cada lectura.
        assertThat(scanner.feed("y"))
            .containsExactly(AnsiToken(AnsiTokenKind.TEXT, "y"))
        assertThat(scanner.flush()).isNull()
    }

    @Test
    fun `una CSI con parametros de pregunta se reconoce entera`() {
        assertThat(feedAll("\u001B[?25h")).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[?25h"),
        )
    }

    @Test
    fun `una OSC terminada en BEL se cierra en el BEL`() {
        val tokens = feedAll("\u001B]0;título\u0007resto")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B]0;título\u0007"),
            AnsiToken(AnsiTokenKind.TEXT, "resto"),
        ).inOrder()
    }

    @Test
    fun `una OSC terminada en ST se cierra en el ST`() {
        val tokens = feedAll("\u001B]8;;https://ejemplo\u001B\\fin")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B]8;;https://ejemplo\u001B\\"),
            AnsiToken(AnsiTokenKind.TEXT, "fin"),
        ).inOrder()
    }

    @Test
    fun `un ST abortado cierra la OSC sin comerse el caracter siguiente`() {
        val tokens = feedAll("\u001B]0;título\u001Bx")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B]0;título"),
            AnsiToken(AnsiTokenKind.TEXT, "x"),
        ).inOrder()
    }

    @Test
    fun `una ESC de dos caracteres se cierra en el segundo`() {
        val tokens = feedAll("\u001B7volver\u001B=M")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B7"),
            AnsiToken(AnsiTokenKind.TEXT, "volver"),
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B="),
            AnsiToken(AnsiTokenKind.TEXT, "M"),
        ).inOrder()
    }

    @Test
    fun `un designador de juego de caracteres ocupa tres caracteres`() {
        val tokens = feedAll("\u001B(Bscala")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001B(B"),
            AnsiToken(AnsiTokenKind.TEXT, "scala"),
        ).inOrder()
    }

    @Test
    fun `una cadena DCS se cierra en su ST`() {
        val tokens = feedAll("\u001BPi datos\u001B\\fin")
        assertThat(tokens).containsExactly(
            AnsiToken(AnsiTokenKind.ESCAPE, "\u001BPi datos\u001B\\"),
            AnsiToken(AnsiTokenKind.TEXT, "fin"),
        ).inOrder()
    }

    @Test
    fun `flush devuelve la secuencia pendiente al cierre del stream`() {
        scanner.feed("abc\u001B[31")
        assertThat(scanner.flush())
            .isEqualTo(AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[31"))
        assertThat(scanner.flush()).isNull()
    }

    @Test
    fun `una secuencia interminable se corta en el tope y el resto va a texto`() {
        val limited = AnsiEscapeScanner(maxEscapeChars = 8)
        val tokens = mutableListOf<AnsiToken>()
        tokens += limited.feed("\u001B[" + "1".repeat(20))
        limited.flush()?.let { tokens += it }
        assertThat(tokens.filter { it.kind == AnsiTokenKind.ESCAPE })
            .containsExactly(AnsiToken(AnsiTokenKind.ESCAPE, "\u001B[111111"))
        val text = tokens.filter { it.kind == AnsiTokenKind.TEXT }.joinToString("") { it.text }
        assertThat(text).isEqualTo("1".repeat(14))
    }
}
