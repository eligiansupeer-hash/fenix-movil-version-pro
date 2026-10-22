// ============================================================================
// Utf8StreamDecoderTest.kt — decodificador UTF-8 incremental (plan B4)
//
// Verifica la recomposición de secuencias partidas entre dos llamadas
// (2, 3 y 4 bytes, en todos los cortes posibles) y la política de errores
// documentada: U+FFFD por byte inválido, secuencia interrumpida que
// reinterpreta el byte ofensivo, surrogados y overlongs rechazados.
// ============================================================================

package com.fenix.movil.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Utf8StreamDecoderTest {
    private val decoder = Utf8StreamDecoder()

    private fun decodeBytes(vararg bytes: Int): String {
        val array = ByteArray(bytes.size) { index -> bytes[index].toByte() }
        return decoder.decode(array, array.size)
    }

    @Test
    fun `el texto ASCII pasa directo`() {
        assertThat(decodeBytes(0x68, 0x6F, 0x6C, 0x61)).isEqualTo("hola")
        assertThat(decoder.flush()).isEmpty()
    }

    @Test
    fun `una llamada vacia no produce nada`() {
        assertThat(decoder.decode(ByteArray(0), 0)).isEmpty()
    }

    @Test
    fun `secuencias de 2 3 y 4 bytes sobreviven a cualquier corte`() {
        for (text in listOf("á", "€", "😀", "año终端")) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (cut in 1 until bytes.size) {
                val d = Utf8StreamDecoder()
                val first = d.decode(bytes.copyOfRange(0, cut), cut)
                val second = d.decode(bytes.copyOfRange(cut, bytes.size), bytes.size - cut)
                val tail = d.flush()
                assertThat(first + second + tail).isEqualTo(text)
            }
        }
    }

    @Test
    fun `byte de arranque invalido produce U+FFFD`() {
        assertThat(decodeBytes(0xFF)).isEqualTo("\uFFFD")
        assertThat(decodeBytes(0xF5)).isEqualTo("\uFFFD")
        assertThat(decodeBytes(0xC1)).isEqualTo("\uFFFD")
    }

    @Test
    fun `una continuacion suelta produce U+FFFD por byte`() {
        assertThat(decodeBytes(0x80, 0xBF)).isEqualTo("\uFFFD\uFFFD")
    }

    @Test
    fun `una secuencia interrumpida reporta U+FFFD y reinterpreta el byte`() {
        // 0xC3 espera continuación y 0x41 ('A') la interrumpe: no se
        // pierde la 'A'.
        assertThat(decodeBytes(0xC3, 0x41)).isEqualTo("\uFFFDA")
    }

    @Test
    fun `un overlong de dos bytes se rechaza por byte`() {
        // C0 AF es el overlong clásico de '/': 0xC0 es lead inválido.
        assertThat(decodeBytes(0xC0, 0xAF)).isEqualTo("\uFFFD\uFFFD")
    }

    @Test
    fun `un overlong de tres bytes completo se rechaza como uno solo`() {
        // E0 80 80 = U+0000 en forma sobre-larga (mínimo 0x800).
        assertThat(decodeBytes(0xE0, 0x80, 0x80)).isEqualTo("\uFFFD")
    }

    @Test
    fun `un surrogate codificado se rechaza`() {
        // ED A0 80 = U+D800 (surrogado), fuera de rango Unicode.
        assertThat(decodeBytes(0xED, 0xA0, 0x80)).isEqualTo("\uFFFD")
    }

    @Test
    fun `un codigo punto fuera de rango se rechaza`() {
        // F4 90 80 80 = U+110000 (> U+10FFFF).
        assertThat(decodeBytes(0xF4, 0x90, 0x80, 0x80)).isEqualTo("\uFFFD")
    }

    @Test
    fun `flush vuelca el residuo como U+FFFD y vuelve a cero`() {
        assertThat(decoder.decode(byteArrayOf(0xE2.toByte()), 1)).isEmpty()
        assertThat(decoder.flush()).isEqualTo("\uFFFD")
        assertThat(decoder.flush()).isEmpty()
        // El decoder queda reutilizable tras el vuelco.
        assertThat(decodeBytes(0x6F, 0x6B)).isEqualTo("ok")
    }

    @Test
    fun `sin residuo flush no agrega nada`() {
        assertThat(decoder.decode("hola".toByteArray(Charsets.UTF_8), 4)).isEqualTo("hola")
        assertThat(decoder.flush()).isEmpty()
    }
}
