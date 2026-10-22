// ============================================================================
// Utf8StreamDecoder.kt — decodificador UTF-8 incremental (plan B4)
// ============================================================================

package com.fenix.movil.terminal

/**
 * Decodificador UTF-8 **incremental**: el stream de una PTY es un flujo de
 * bytes sin fronteras de carácter, así que una secuencia de 2–4 bytes puede
 * partirse entre dos `read()`. Este decoder retiene el inicio incompleto y
 * lo completa con la siguiente llamada.
 *
 * Política de errores (documentada y testeada):
 * - byte de arranque inválido (`0x80–0xC1`, `0xF5–0xFF`) → `U+FFFD`.
 * - continuación inválida → `U+FFFD` para la secuencia truncada y el byte
 *   ofensivo **se reinterpreta** desde cero (un `0x41` que interrumpe un
 *   `0xC3` produce `U+FFFD` + `'A'`, no se pierde la `A`).
 * - secuencia completa pero sobre-larga/surrogado/fuera de rango → `U+FFFD`.
 * - bytes residuales al cierre del stream → un solo `U+FFFD` ([flush]).
 *
 * No usa `CharsetDecoder` de NIO porque su API de conveniencia hace
 * `flush()` implícito (incompatible con llamadas incrementales) y la
 * incremental exige gestionar el búfer de entrada a mano de todos modos.
 */
internal class Utf8StreamDecoder {
    private val pending = ByteArray(MAX_SEQUENCE_BYTES)
    private var pendingLength = 0
    private var pendingNeed = 0
    private val out = StringBuilder()

    /**
     * Decodifica [length] bytes iniciales de [bytes] (puede contener
     * secuencias residuales de la llamada anterior). Devuelve el texto
     * completo disponible; la secuencia incompleta final queda retenida.
     */
    fun decode(bytes: ByteArray, length: Int): String {
        out.setLength(0)
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (pendingLength == 0) {
                if (b < 0x80) {
                    out.append(b.toChar())
                    i++
                    continue
                }
                val need = leadNeed(b)
                if (need == 0) {
                    out.append(REPLACEMENT)
                    i++
                    continue
                }
                pendingNeed = need
                pending[pendingLength++] = b.toByte()
                i++
                continue
            }
            if (b !in 0x80..0xBF) {
                // La secuencia retenida se truncó: se reporta y el byte
                // actual vuelve a procesarse desde el inicio.
                out.append(REPLACEMENT)
                pendingLength = 0
                continue
            }
            pending[pendingLength++] = b.toByte()
            i++
            if (pendingLength == pendingNeed) {
                appendCompleted()
                pendingLength = 0
            }
        }
        return out.toString()
    }

    /** Vuelca la secuencia incompleta al cierre del stream como `U+FFFD`. */
    fun flush(): String {
        if (pendingLength == 0) return ""
        pendingLength = 0
        pendingNeed = 0
        return REPLACEMENT.toString()
    }

    private fun appendCompleted() {
        val codePoint = decodeCodePoint()
        val minimum = when (pendingNeed) {
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }
        val valid = codePoint >= minimum &&
            codePoint !in 0xD800..0xDFFF &&
            codePoint <= 0x10FFFF
        if (valid) {
            out.appendCodePoint(codePoint)
        } else {
            out.append(REPLACEMENT)
        }
    }

    private fun decodeCodePoint(): Int = when (pendingNeed) {
        2 -> ((pending[0].toInt() and 0x1F) shl 6) or
            (pending[1].toInt() and 0x3F)
        3 -> ((pending[0].toInt() and 0x0F) shl 12) or
            ((pending[1].toInt() and 0x3F) shl 6) or
            (pending[2].toInt() and 0x3F)
        else -> ((pending[0].toInt() and 0x07) shl 18) or
            ((pending[1].toInt() and 0x3F) shl 12) or
            ((pending[2].toInt() and 0x3F) shl 6) or
            (pending[3].toInt() and 0x3F)
    }

    private fun leadNeed(b: Int): Int = when (b) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0 // 0x80–0xC1 (continuación suelta o sobre-largo) y 0xF5–0xFF
    }

    private companion object {
        const val MAX_SEQUENCE_BYTES = 4
        const val REPLACEMENT = '\uFFFD'
    }
}
