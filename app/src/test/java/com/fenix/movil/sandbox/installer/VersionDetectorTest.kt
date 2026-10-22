// ============================================================================
// VersionDetectorTest.kt — parseo/comparación de versiones (plan B5)
//
// Cobertura: formatos reales de CLI (`1.18.21`, `v22.23.3`, texto mixto),
// salida inutilizable → null, comparación numérica por segmento y las tres
// ramas de satisfies (sin versión, sin mínimo, con mínimo).
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionDetectorTest {
    // ------------------------------------------------------------------
    // parse
    // ------------------------------------------------------------------

    @Test
    fun `parse extrae version limpia`() {
        assertThat(VersionDetector.parse("1.18.21")).isEqualTo("1.18.21")
    }

    @Test
    fun `parse descarta la v inicial`() {
        assertThat(VersionDetector.parse("v22.23.3")).isEqualTo("22.23.3")
    }

    @Test
    fun `parse extrae el primer token numerico de texto mixto`() {
        assertThat(VersionDetector.parse("opencode 1.2.3 (build 99)")).isEqualTo("1.2.3")
    }

    @Test
    fun `parse tolera sufijo de prelanzamiento`() {
        assertThat(VersionDetector.parse("1.2.3-beta.1")).isEqualTo("1.2.3")
    }

    @Test
    fun `parse devuelve null sin salida parseable`() {
        assertThat(VersionDetector.parse("command not found")).isNull()
    }

    @Test
    fun `parse devuelve null con salida nula o vacia`() {
        assertThat(VersionDetector.parse(null)).isNull()
        assertThat(VersionDetector.parse("")).isNull()
        assertThat(VersionDetector.parse("   \n")).isNull()
    }

    @Test
    fun `parse ignora el stderr si el stdout trae la version`() {
        assertThat(VersionDetector.parse("Node.js v18.20.4\nwarn: algo")).isEqualTo("18.20.4")
    }

    // ------------------------------------------------------------------
    // compare
    // ------------------------------------------------------------------

    @Test
    fun `compare es numerico y no lexicografico`() {
        assertThat(VersionDetector.compare("1.10.0", "1.9.9")).isEqualTo(1)
        assertThat(VersionDetector.compare("1.9.9", "1.10.0")).isEqualTo(-1)
    }

    @Test
    fun `compare trata los segmentos ausentes como cero`() {
        assertThat(VersionDetector.compare("1.2", "1.2.0")).isEqualTo(0)
        assertThat(VersionDetector.compare("2", "2.0.1")).isEqualTo(-1)
        assertThat(VersionDetector.compare("2.0.1", "2")).isEqualTo(1)
    }

    @Test
    fun `compare con versiones iguales es cero`() {
        assertThat(VersionDetector.compare("1.18.21", "1.18.21")).isEqualTo(0)
    }

    // ------------------------------------------------------------------
    // satisfies
    // ------------------------------------------------------------------

    @Test
    fun `satisfies rechaza la ausencia de version`() {
        assertThat(VersionDetector.satisfies(null, null)).isFalse()
        assertThat(VersionDetector.satisfies(null, "1.0.0")).isFalse()
    }

    @Test
    fun `satisfies sin minimo acepta cualquier version funcional`() {
        assertThat(VersionDetector.satisfies("1.0.0", null)).isTrue()
        assertThat(VersionDetector.satisfies("0.0.1", null)).isTrue()
    }

    @Test
    fun `satisfies aplica el piso minimo`() {
        assertThat(VersionDetector.satisfies("1.2.3", "1.2.3")).isTrue()
        assertThat(VersionDetector.satisfies("1.3.0", "1.2.3")).isTrue()
        assertThat(VersionDetector.satisfies("1.2.2", "1.2.3")).isFalse()
    }
}
