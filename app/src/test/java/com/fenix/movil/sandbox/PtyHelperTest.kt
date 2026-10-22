// ============================================================================
// PtyHelperTest.kt — helper de sesión PTY del guest (plan B4)
//
// Verifica el contrato del helper sin proot: generación determinista,
// instalación en el directorio compartido, limpieza del canal de una
// sesión anterior y — con los binarios del host — la sintaxis del script
// real (`sh -n`) y los códigos de salida de los guardarriles (74 sin
// script(1), 71 sin tty).
// ============================================================================

package com.fenix.movil.sandbox

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PtyHelperTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var share: File

    @Before
    fun setUp() {
        share = tmp.newFolder("share")
    }

    private fun runHelper(
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        stdin: File? = null,
    ): Pair<Int, String> {
        val builder = ProcessBuilder(listOf("/bin/sh", share.resolve(PtyHelper.SCRIPT_NAME).path) + args)
            .redirectErrorStream(true)
        if (stdin != null) {
            builder.redirectInput(ProcessBuilder.Redirect.from(stdin))
        }
        if (env.isNotEmpty()) {
            val child = builder.environment()
            child.clear()
            child.putAll(env)
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "el helper no terminó en 10 s" }
        return process.exitValue() to output
    }

    // ------------------------------------------------------------------
    // Generación e instalación
    // ------------------------------------------------------------------

    @Test
    fun `scriptContent es determinista y no deja marcadores sin sustituir`() {
        val first = PtyHelper.scriptContent()
        val second = PtyHelper.scriptContent()
        assertThat(first).isEqualTo(second)
        assertThat(first).doesNotContain("@@")
        assertThat(first).startsWith("#!/bin/sh")
        // El template usa \$ como marcador interno: el resultado real usa $.
        assertThat(first).contains("share=\"${'$'}{FENIX_SHARE:-/fenix}\"")
        assertThat(first).contains("stty rows \"${'$'}rows\" cols \"${'$'}cols\"")
    }

    @Test
    fun `install escribe el helper ejecutable y determinista`() {
        val script = PtyHelper.install(share)
        assertThat(script.name).isEqualTo(PtyHelper.SCRIPT_NAME)
        assertThat(script.canExecute()).isTrue()
        assertThat(script.readText(Charsets.UTF_8)).isEqualTo(PtyHelper.scriptContent())
        // Reinstalar produce exactamente los mismos bytes.
        PtyHelper.install(share)
        assertThat(script.readText(Charsets.UTF_8)).isEqualTo(PtyHelper.scriptContent())
    }

    @Test
    fun `install crea el directorio si no existe`() {
        val missing = File(share, "sub/dir/share")
        val script = PtyHelper.install(missing)
        assertThat(script.isFile).isTrue()
        assertThat(script.canExecute()).isTrue()
    }

    @Test
    fun `install limpia el canal de una sesion anterior`() {
        PtyHelper.ctrlFile(share).writeText("30 100\n")
        PtyHelper.sizeFile(share).writeText("30 100\n")
        PtyHelper.install(share)
        assertThat(PtyHelper.ctrlFile(share).exists()).isFalse()
        assertThat(PtyHelper.sizeFile(share).exists()).isFalse()
    }

    @Test
    fun `los nombres del canal siguen el protocolo`() {
        assertThat(PtyHelper.ctrlFile(share)).isEqualTo(File(share, PtyHelper.CTRL_NAME))
        assertThat(PtyHelper.sizeFile(share)).isEqualTo(File(share, PtyHelper.SIZE_NAME))
        assertThat(PtyHelper.SCRIPT_NAME).isEqualTo("fenix-pty.sh")
        assertThat(PtyHelper.CTRL_NAME).isEqualTo(".fenix-pty-ctrl")
        assertThat(PtyHelper.SIZE_NAME).isEqualTo(".fenix-pty-size")
    }

    // ------------------------------------------------------------------
    // El script real, con los binarios del host
    // ------------------------------------------------------------------

    @Test
    fun `el helper generado pasa la sintaxis de sh`() {
        Assume.assumeTrue(File("/bin/sh").canExecute())
        PtyHelper.install(share)
        val builder = ProcessBuilder("/bin/sh", "-n", share.resolve(PtyHelper.SCRIPT_NAME).path)
        val process = builder.start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "sh -n no terminó en 10 s" }
        assertThat(process.exitValue()).isEqualTo(0)
    }

    @Test
    fun `el rol exterior sin script(1) sale con 74`() {
        Assume.assumeTrue(File("/bin/sh").canExecute())
        PtyHelper.install(share)
        val (exit, output) = runHelper(
            env = mapOf(
                "PATH" to "/no-existe",
                "FENIX_SHARE" to share.absolutePath,
            ),
        )
        assertThat(exit).isEqualTo(74)
        assertThat(output).contains("falta script(1)")
    }

    @Test
    fun `el rol --inside fuera de una PTY sale con 71`() {
        Assume.assumeTrue(File("/bin/sh").canExecute() && File("/usr/bin/tty").canExecute())
        PtyHelper.install(share)
        val (exit, _) = runHelper(
            args = listOf("--inside"),
            env = mapOf(
                "PATH" to "/usr/bin:/bin",
                "FENIX_SHARE" to share.absolutePath,
                "FENIX_ROWS" to "24",
                "FENIX_COLS" to "80",
                "FENIX_SHELL" to "/bin/sh",
            ),
            stdin = File("/dev/null"),
        )
        assertThat(exit).isEqualTo(71)
    }
}
