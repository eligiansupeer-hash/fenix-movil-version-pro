// ============================================================================
// TerminalSize.kt — geometría de la ventana de la terminal (plan B4)
// ============================================================================

package com.fenix.movil.terminal

/**
 * Ventana de la terminal en celdas de carácter: **filas × columnas**.
 *
 * Es la unidad que viaja por todo B4: [ShellSession.resize] la valida aquí,
 * el transporte la propaga al tty (en el guest se aplica con
 * `stty rows R cols C` → `TIOCSWINSZ` → `SIGWINCH`) y la vista VT (D3) la
 * usa para plegar el scrollback.
 *
 * El constructor valida el rango de forma estricta: una ventana de 0 filas
 * colapsa readline en el guest y un valor gigante desperdicia memoria en la
 * vista, así que ambos casos son [IllegalArgumentException] **antes** de
 * tocar el transporte.
 */
data class TerminalSize(
    val rows: Int,
    val cols: Int,
) {
    init {
        require(rows in MIN..MAX) { "rows debe estar en $MIN..$MAX (valor: $rows)" }
        require(cols in MIN..MAX) { "cols debe estar en $MIN..$MAX (valor: $cols)" }
    }

    /** Cadena del protocolo de redimensión: `"filas columnas"`. */
    fun toProtocol(): String = "$rows $cols"

    companion object {
        /** Mínimos aceptados (una terminal de 1×1 es inútil pero válida). */
        const val MIN = 1

        /** Máximo aceptado: por encima de esto la vista VT no aporta nada. */
        const val MAX = 999

        /** Geometría por defecto, idéntica a la que se envía al guest. */
        val DEFAULT: TerminalSize = TerminalSize(24, 80)
    }
}
