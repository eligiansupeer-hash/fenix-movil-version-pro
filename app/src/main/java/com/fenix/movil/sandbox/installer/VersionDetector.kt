// ============================================================================
// VersionDetector.kt — parseo y comparación de versiones de CLIs (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

/**
 * Utilidades de versión para la verificación de CLIs instalados en el
 * sandbox (plan B5).
 *
 * Los binarios de CLI imprimen su versión en formatos heterogéneos
 * (`1.18.21`, `v22.23.3`, `opencode 1.2.3 (build …)`), así que el parseo
 * extrae el **primer token numérico con puntos** de la salida y normaliza
 * el prefijo `v`. La comparación es **numérica por segmento** (no
 * lexicográfica: `1.10.0 > 1.9.9`) y los segmentos ausentes valen `0`
 * (`1.2 == 1.2.0`).
 *
 * Los sufijos de pre-lanzamiento (`1.2.3-beta`) se truncan al número base:
 * una verificación de "¿está instalado y dentro del rango mínimo?" no debe
 * rechazar un build por su sufijo; documentado en `docs/sandbox-clis.md`.
 */
object VersionDetector {
    /** Primer token numérico con puntos de una salida (`v` inicial excluido). */
    private val VERSION_TOKEN = Regex("\\d+(?:\\.\\d+)*")

    /** Extrae la versión de la salida de `<binario> --version`; `null` si no hay. */
    fun parse(output: String?): String? {
        if (output.isNullOrBlank()) return null
        return VERSION_TOKEN.find(output)?.value
    }

    /**
     * ¿La versión detectada satisface el requisito del CLI?
     *
     * - `detected == null` → `false` (no instalado, o el binario falló).
     * - `minVersion == null` → `true` con cualquier versión funcional: la
     *   política por defecto es "que el CLI arranque y responda", no
     *   pinnear un mínimo (ver `docs/sandbox-clis.md`).
     */
    fun satisfies(detected: String?, minVersion: String?): Boolean {
        if (detected == null) return false
        if (minVersion == null) return true
        return compare(detected, minVersion) >= 0
    }

    /** Comparación numérica por segmento; segmento ausente = 0. */
    fun compare(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        val depth = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until depth) {
            val leftNumber = segmentNumber(leftParts, index)
            val rightNumber = segmentNumber(rightParts, index)
            if (leftNumber != rightNumber) return leftNumber.compareTo(rightNumber)
        }
        return 0
    }

    private fun segmentNumber(parts: List<String>, index: Int): Int {
        val token = parts.getOrNull(index).orEmpty()
        val digits = token.takeWhile { character -> character.isDigit() }
        return digits.toIntOrNull() ?: 0
    }
}
