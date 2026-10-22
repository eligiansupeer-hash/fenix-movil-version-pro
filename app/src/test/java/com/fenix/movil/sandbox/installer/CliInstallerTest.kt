// ============================================================================
// CliInstallerTest.kt — orquestador del instalador (plan B5)
//
// Cobertura: máquina de fases (CHECKING→…→READY/NO_NETWORK/FAILED), red
// ausente no ejecuta instalación, todo instalado no pide red, secuencia
// exacta de comandos con sus timeouts, fallo de paso corta el resto, y
// error del runner se reporta como FAILED sin propagarse.
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

// --- Dobles de prueba -------------------------------------------------------

/** Runner que registra cada comando y responde según un guion por paso. */
private class FakeRunner(
    private val responses: List<GuestResult>,
) : GuestCommandRunner {
    val commands = mutableListOf<List<String>>()
    val timeouts = mutableListOf<Long>()
    private var cursor = 0

    override suspend fun run(command: List<String>, timeoutMs: Long): GuestResult {
        commands += command
        timeouts += timeoutMs
        val index = cursor++
        return responses.getOrElse(index) { GuestResult(0, "", "", timedOut = false) }
    }
}

/** Runner que lanza IOException al primer comando (sandbox caído). */
private class ExplodingRunner : GuestCommandRunner {
    override suspend fun run(command: List<String>, timeoutMs: Long): GuestResult =
        throw IOException("proot no arranca")
}

private fun ok(output: String) = GuestResult(0, output, "", timedOut = false)
private fun fail(code: Int, stderr: String) = GuestResult(code, "", stderr, timedOut = false)

// --- Tests ------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class CliInstallerTest {

    @Test
    fun `constructor con registro vacio se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliInstaller(
                networkProbe = { true },
                runner = FakeRunner(emptyList()),
                specs = emptyList(),
            )
        }
    }

    @Test
    fun `todo instalado y offline termina en READY sin pasos`() = runTest {
        // Sondeos iniciales de los 3 CLIs (CHECKING); sin plan que correr.
        val runner = FakeRunner(listOf(ok("1.18.21"), ok("2.0.1"), ok("0.9.0")))
        val installer = CliInstaller(
            networkProbe = { false },
            runner = runner,
        )

        val report = installer.runInstall()

        assertThat(report.phase).isEqualTo(InstallerPhase.READY)
        assertThat(report.stepsTotal).isEqualTo(0)
        assertThat(report.cliStatuses).hasSize(3)
        assertThat(report.cliStatuses.all { status -> status.satisfied }).isTrue()
        // Solo los sondeos de verificación, ningún comando de instalación.
        assertThat(runner.commands).hasSize(3)
        assertThat(
            runner.commands.none { cmd -> cmd.joinToString(" ").contains("install") },
        ).isTrue()
    }

    @Test
    fun `faltan CLIs y no hay red y NO_NETWORK no ejecuta instalacion`() = runTest {
        // 3 sondeos: opencode ausente; luego nada más (NO_NETWORK corta).
        val runner = FakeRunner(listOf(ok("bad"), fail(127, "no such file"), fail(127, "")))
        val installer = CliInstaller(
            networkProbe = { false },
            runner = runner,
        )

        val report = installer.runInstall()

        assertThat(report.phase).isEqualTo(InstallerPhase.NO_NETWORK)
        assertThat(report.stepsTotal).isEqualTo(0)
        assertThat(report.message).contains("sin red")
        // Ningún paso de instalación (apt/npm) llegó a ejecutarse.
        assertThat(
            runner.commands.map { cmd -> cmd.joinToString(" ") }
                .any { text -> text.contains("apt-get") || text.contains("npm install") },
        ).isFalse()
    }

    @Test
    fun `con red y nada instalado la secuencia es apt y luego cada npm`() = runTest {
        // Guion: 3 sondeos (todo ausente) + apt + 3 npm + 3 sondeos finales.
        val responses = buildList {
            repeat(3) { add(fail(127, "not installed")) } // CHECKING
            add(ok("")) // apt:base
            repeat(3) { add(ok("")) } // npm de cada CLI
            repeat(3) { add(ok("9.9.9")) } // VERIFYING
        }
        val runner = FakeRunner(responses)
        val installer = CliInstaller(
            networkProbe = { true },
            runner = runner,
        )

        val report = installer.runInstall()

        assertThat(report.phase).isEqualTo(InstallerPhase.READY)
        assertThat(report.stepsTotal).isEqualTo(4)
        assertThat(report.stepsDone).isEqualTo(4)
        // 3 sondeos + 4 pasos + 3 sondeos finales.
        assertThat(runner.commands).hasSize(10)
        assertThat(runner.commands[3].joinToString(" ")).contains("apt-get install")
        assertThat(runner.commands[4].joinToString(" ")).contains("npm install -g")
        assertThat(runner.timeouts.drop(3).take(4))
            .containsExactly(
                CliInstaller.DEFAULT_INSTALL_TIMEOUT_MS,
                CliInstaller.DEFAULT_INSTALL_TIMEOUT_MS,
                CliInstaller.DEFAULT_INSTALL_TIMEOUT_MS,
                CliInstaller.DEFAULT_INSTALL_TIMEOUT_MS,
            )
            .inOrder()
    }

    @Test
    fun `un fallo en un paso corta el resto y cierra en FAILED`() = runTest {
        // 3 sondeos + apt (fallo) → sin npm posteriores.
        val responses = buildList {
            repeat(3) { add(fail(127, "")) }
            add(fail(100, "E: Unable to locate package"))
        }
        val runner = FakeRunner(responses)
        val installer = CliInstaller(
            networkProbe = { true },
            runner = runner,
        )

        val report = installer.runInstall()

        assertThat(report.phase).isEqualTo(InstallerPhase.FAILED)
        assertThat(report.currentStepId).isEqualTo(InstallPlan.APT_STEP_ID)
        assertThat(report.message).contains("exit 100")
        // 3 sondeos + 1 paso fallido; ningún npm se intentó.
        assertThat(runner.commands).hasSize(4)
        assertThat(report.stepsTotal).isEqualTo(4)
        assertThat(report.stepsDone).isEqualTo(1)
    }

    @Test
    fun `un runner que revienta se reporta como FAILED sin propagarse`() = runTest {
        val installer = CliInstaller(
            networkProbe = { true },
            runner = ExplodingRunner(),
        )

        val report = installer.runInstall()

        assertThat(report.phase).isEqualTo(InstallerPhase.FAILED)
        // El sondeo de CHECKING tolera la excepción y sigue.
    }

    @Test
    fun `el reporte expone la progresion de fases como flujo`() = runTest {
        val responses = buildList {
            repeat(3) { add(ok("1.0.0")) }
        }
        val installer = CliInstaller(
            networkProbe = { false },
            runner = FakeRunner(responses),
        )

        val phases = mutableListOf<InstallerPhase>()
        val collector = launch { installer.report.collect { phases += it.phase } }

        // `StateFlow` confluye: sin transiciones intermedias el colector ve
        // el arranque (IDLE) y, tras yield, el terminal publicado (READY).
        assertThat(installer.report.value.phase).isEqualTo(InstallerPhase.IDLE)
        runCurrent()
        installer.runInstall()
        advanceUntilIdle()

        // El collector sigue vivo (un StateFlow nunca completa), así que lo
        // soltamos para no bloquear el fin del test.
        collector.cancel()

        assertThat(phases).contains(InstallerPhase.IDLE)
        assertThat(phases).contains(InstallerPhase.READY)
        assertThat(installer.report.value.phase).isEqualTo(InstallerPhase.READY)
    }

    @Test
    fun `todo instalado publica la fase terminal en el StateFlow`() = runTest {
        val n = CliSpec.defaultRegistry().size
        val runner = FakeRunner(List(n) { ok("1.2.3") })
        // Red irrelevante: el CHECKING ve todo instalado y nunca consulta.
        val installer = CliInstaller(runner = runner, networkProbe = { false })

        val report = installer.runInstall()

        // Sin collector: el StateFlow confluye, así que el valor final es
        // el terminal (nunca se queda en IDLE/CHECKING tras volver).
        assertThat(installer.report.value.phase).isEqualTo(report.phase)
        assertThat(installer.report.value.phase).isEqualTo(InstallerPhase.READY)
        // Solo sondeos de verificación: ningún paso de instalación.
        assertThat(runner.commands).hasSize(n)
    }

    @Test
    fun `el constructor valida timeouts no positivos`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliInstaller(
                networkProbe = { true },
                runner = FakeRunner(emptyList()),
                verifyTimeoutMs = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliInstaller(
                networkProbe = { true },
                runner = FakeRunner(emptyList()),
                installTimeoutMs = -1,
            )
        }
    }
}
