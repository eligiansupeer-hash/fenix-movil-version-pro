// ============================================================================
// TerminalSizeTest.kt — geometría de la terminal (plan B4)
//
// El validador vive en el constructor: todo lo que viaja por el protocolo
// de redimensión (ShellSession.resize → transporte → stty) se comprueba
// aquí, antes de tocar el transporte.
// ============================================================================

package com.fenix.movil.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalSizeTest {

    @Test
    fun `la geometria por defecto es 24x80`() {
        assertThat(TerminalSize.DEFAULT).isEqualTo(TerminalSize(24, 80))
        assertThat(TerminalSize.DEFAULT.rows).isEqualTo(24)
        assertThat(TerminalSize.DEFAULT.cols).isEqualTo(80)
    }

    @Test
    fun `toProtocol produce filas espacio columnas`() {
        assertThat(TerminalSize(30, 100).toProtocol()).isEqualTo("30 100")
        assertThat(TerminalSize(1, 1).toProtocol()).isEqualTo("1 1")
        assertThat(TerminalSize(999, 999).toProtocol()).isEqualTo("999 999")
    }

    @Test
    fun `los limites 1 y 999 son validos`() {
        assertThat(TerminalSize(1, 1)).isEqualTo(TerminalSize(1, 1))
        assertThat(TerminalSize(999, 999)).isEqualTo(TerminalSize(999, 999))
    }

    @Test
    fun `filas fuera de rango se rechazan`() {
        for (bad in listOf(0, -1, 1_000, Int.MAX_VALUE)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                TerminalSize(bad, 80)
            }
            assertThat(error).hasMessageThat().contains("rows")
            assertThat(error).hasMessageThat().contains(bad.toString())
        }
    }

    @Test
    fun `columnas fuera de rango se rechazan`() {
        for (bad in listOf(0, -5, 1_000, Int.MIN_VALUE)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                TerminalSize(24, bad)
            }
            assertThat(error).hasMessageThat().contains("cols")
            assertThat(error).hasMessageThat().contains(bad.toString())
        }
    }
}
