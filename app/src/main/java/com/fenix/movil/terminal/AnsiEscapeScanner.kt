// ============================================================================
// AnsiEscapeScanner.kt — segmentación de secuencias de escape ANSI (plan B4)
// ============================================================================

package com.fenix.movil.terminal

/** Tipo de token producido por [AnsiEscapeScanner]. */
internal enum class AnsiTokenKind {
    TEXT,
    ESCAPE,
}

/** Token de salida: texto literal o secuencia de escape cruda. */
internal data class AnsiToken(
    val kind: AnsiTokenKind,
    val text: String,
)

/**
 * Segmenta el flujo de caracteres de la terminal en **texto literal** y
 * **secuencias de escape ANSI** conservadas crudas.
 *
 * ¿Por qué segmentar en B4 y no en D3? Porque las secuencias llegan
 * partidas: `ESC [ 3` en un `read()` y `1m ROJO` en el siguiente. Un
 * escáner con estado mantiene la secuencia pendiente y emite un único
 * token completo cuando llega su byte final; el scrollback
 * ([ShellSession.plainText]) queda libre de códigos y la vista VT recibe
 * la secuencia entera e interpretable.
 *
 * Gramática cubierta (ECMA-48 / xterm):
 * - `ESC [ … final` (CSI): final en `0x40–0x7E` (SGR, cursor, erase…).
 * - `ESC ] … BEL|ST` (OSC): títulos, clipboard; también `ESC \` (ST).
 * - `ESC P … ST` / `ESC X` / `ESC ^` / `ESC _` (DCS/SOS/PM/APC).
 * - `ESC ( c`, `ESC ) c`, `ESC * c`, `ESC + c`, `ESC # c`, `ESC % c`
 *   (designador de juego de caracteres, 3 caracteres).
 * - Cualquier otra secuencia de 2 caracteres (`ESC 7`, `ESC =`, `ESC M`…).
 *
 * Protecciones: cada secuencia se limita a [maxEscapeChars] para que una
 * salida corrupta no hinche la memoria (al superarlo se emite lo acumulado
 * y se vuelve a texto); [flush] devuelve la secuencia pendiente al cerrar
 * el stream para no perder bytes.
 */
internal class AnsiEscapeScanner(
    private val maxEscapeChars: Int = DEFAULT_MAX_ESCAPE_CHARS,
) {
    private enum class Mode { TEXT, ESC, CSI, OSC, OSC_ESC, STRING, STRING_ESC, CHARSET }

    private var mode = Mode.TEXT
    private val text = StringBuilder()
    private val escape = StringBuilder()

    /** Segmenta [chunk]; emite los tokens completos en orden. */
    fun feed(chunk: String): List<AnsiToken> {
        val tokens = mutableListOf<AnsiToken>()
        var i = 0
        while (i < chunk.length) {
            val c = chunk[i]
            when (mode) {
                Mode.TEXT -> {
                    if (c == ESC) {
                        flushText(tokens)
                        mode = Mode.ESC
                        escape.append(c)
                    } else {
                        text.append(c)
                    }
                    i++
                }
                Mode.ESC -> {
                    when (c) {
                        '[' -> { mode = Mode.CSI; escape.append(c) }
                        ']' -> { mode = Mode.OSC; escape.append(c) }
                        'P', 'X', '^', '_' -> { mode = Mode.STRING; escape.append(c) }
                        '(', ')', '*', '+', '#', '%' -> { mode = Mode.CHARSET; escape.append(c) }
                        else -> {
                            escape.append(c)
                            flushEscape(tokens)
                            mode = Mode.TEXT
                        }
                    }
                    i++
                }
                Mode.CHARSET -> {
                    // Designador de 3er carácter: ESC ( B, ESC % G, etc.
                    escape.append(c)
                    flushEscape(tokens)
                    mode = Mode.TEXT
                    i++
                }
                Mode.CSI -> {
                    escape.append(c)
                    i++
                    if (c.code in 0x40..0x7E || escape.length >= maxEscapeChars) {
                        flushEscape(tokens)
                        mode = Mode.TEXT
                    }
                }
                Mode.OSC -> {
                    when {
                        c == BEL -> {
                            escape.append(c)
                            flushEscape(tokens)
                            mode = Mode.TEXT
                            i++
                        }
                        c == ESC -> { mode = Mode.OSC_ESC; escape.append(c); i++ }
                        else -> {
                            escape.append(c)
                            i++
                            if (escape.length >= maxEscapeChars) {
                                flushEscape(tokens)
                                mode = Mode.TEXT
                            }
                        }
                    }
                }
                Mode.OSC_ESC -> {
                    if (c == '\\') {
                        escape.append(c)
                        flushEscape(tokens)
                        mode = Mode.TEXT
                        i++
                    } else {
                        // ST abortado: la OSC se cierra sin el ESC
                        // abortador (el carácter actual se reprocesa).
                        dropTrailingEsc()
                        flushEscape(tokens)
                        mode = Mode.TEXT
                    }
                }
                Mode.STRING -> {
                    if (c == ESC) {
                        mode = Mode.STRING_ESC
                        escape.append(c)
                        i++
                    } else {
                        escape.append(c)
                        i++
                        if (escape.length >= maxEscapeChars) {
                            flushEscape(tokens)
                            mode = Mode.TEXT
                        }
                    }
                }
                Mode.STRING_ESC -> {
                    if (c == '\\') {
                        escape.append(c)
                        flushEscape(tokens)
                        mode = Mode.TEXT
                        i++
                    } else {
                        // ST abortado: la cadena se cierra sin el ESC
                        // abortador (el carácter actual se reprocesa).
                        dropTrailingEsc()
                        flushEscape(tokens)
                        mode = Mode.TEXT
                    }
                }
            }
        }
        // El texto que termina el chunk se emite ya, sin esperar a un
        // escape ni al cierre: el scrollback y la vista deben reflejar
        // cada lectura del stream en cuanto llega.
        if (mode == Mode.TEXT) flushText(tokens)
        return tokens
    }

    /** Vuelca el texto o la secuencia pendiente (cierre de stream). */
    fun flush(): AnsiToken? = when (mode) {
        Mode.TEXT -> flushTextToken()
        else -> flushEscapeToken()
    }

    private fun flushText(tokens: MutableList<AnsiToken>) {
        flushTextToken()?.let(tokens::add)
    }

    private fun flushTextToken(): AnsiToken? {
        if (text.isEmpty()) return null
        val token = AnsiToken(AnsiTokenKind.TEXT, text.toString())
        text.setLength(0)
        return token
    }

    private fun flushEscape(tokens: MutableList<AnsiToken>) {
        flushEscapeToken()?.let(tokens::add)
    }

    /** Quita el ESC que abrió una secuencia abortada (no se conserva). */
    private fun dropTrailingEsc() {
        if (escape.isNotEmpty() && escape.last() == ESC) {
            escape.deleteCharAt(escape.length - 1)
        }
    }

    private fun flushEscapeToken(): AnsiToken? {
        if (escape.isEmpty()) return null
        val token = AnsiToken(AnsiTokenKind.ESCAPE, escape.toString())
        escape.setLength(0)
        return token
    }

    private companion object {
        const val ESC = '\u001B'
        const val BEL = '\u0007'

        /**
         * Techo de una sola secuencia: SGR/CSI reales rara vez superan 64
         * caracteres; 8 KB es un margen enorme que además acota la memoria
         * ante salida corrupta.
         */
        const val DEFAULT_MAX_ESCAPE_CHARS = 8_192
    }
}
