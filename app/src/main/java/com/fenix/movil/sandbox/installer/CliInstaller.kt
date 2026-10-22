// ============================================================================
// CliInstaller.kt — orquestador del instalador de CLIs (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Instalador de CLIs dentro del sandbox (plan B5).
 *
 * Ciclo de [runInstall] (serializado por un `Mutex`: dos toques en la UI
 * no ejecutan dos planes a la vez):
 *
 * 1. **CHECKING** — sondea `<binario> --version` de cada CLI del registro
 *    y construye los [CliStatus].
 * 2. Construye el [InstallPlan] con lo detectado. Si no hay pendientes →
 *    **READY** (sin red, sin pasos).
 * 3. Si hay pendientes y [NetworkProbe] dice que no hay red →
 *    **NO_NETWORK** sin ejecutar ningún paso de instalación.
 * 4. **INSTALLING** — ejecuta los pasos del plan en orden; el primer
 *    fallo (código ≠ 0, timeout o error de lanzamiento) corta el resto y
 *    cierra en **FAILED** con el detalle del paso.
 * 5. **VERIFYING** — vuelve a sondear versiones; todos satisfechos →
 *    **READY**, si no → **FAILED**.
 *
 * Toda la salida queda en [report] (`StateFlow<InstallerReport>`).
 *
 * @param networkProbe detección de red (doble en pruebas).
 * @param runner ejecución de comandos en el guest (doble en pruebas).
 * @param specs registro de CLIs; por defecto [CliSpec.defaultRegistry].
 * @param verifyTimeoutMs tope por sondeo de versión.
 * @param installTimeoutMs tope por paso de instalación (apt/npm puede
 *   tardar minutos en un teléfono).
 */
class CliInstaller(
    private val networkProbe: NetworkProbe,
    private val runner: GuestCommandRunner,
    private val specs: List<CliSpec> = CliSpec.defaultRegistry(),
    private val verifyTimeoutMs: Long = DEFAULT_VERIFY_TIMEOUT_MS,
    private val installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS,
) {
    private val mutex = Mutex()

    private val _report = MutableStateFlow(InstallerReport())
    val report: StateFlow<InstallerReport> = _report.asStateFlow()

    init {
        require(specs.isNotEmpty()) { "registro de CLIs vacío" }
        require(verifyTimeoutMs > 0) { "verifyTimeoutMs debe ser > 0" }
        require(installTimeoutMs > 0) { "installTimeoutMs debe ser > 0" }
    }

    /**
     * Ejecuta el ciclo completo. Devuelve el reporte final (el mismo que
     * [report].value al terminar).
     */
    suspend fun runInstall(): InstallerReport = mutex.withLock {
        _report.value = InstallerReport(
            phase = InstallerPhase.CHECKING,
            message = "comprobando CLIs instalados",
        )
        val statuses = probeStatuses()
        val detected = statuses.associate { status -> status.specId to status.version }
        val plan = InstallPlan.build(specs, detected)

        if (plan.pendingSpecs.isEmpty()) {
            return@withLock publish(
                InstallerReport(
                    phase = InstallerPhase.READY,
                    message = "todos los CLIs ya están instalados",
                    cliStatuses = statuses,
                ),
            )
        }
        if (!networkProbe.isOnline()) {
            return@withLock publish(
                InstallerReport(
                    phase = InstallerPhase.NO_NETWORK,
                    message = "sin red: faltan ${plan.pendingSpecs.size} CLI(s) por instalar",
                    cliStatuses = statuses,
                ),
            )
        }

        val failed = executeSteps(plan, statuses)
        if (failed != null) return@withLock publish(failed)

        val finalStatuses = probeStatuses()
        val missing = finalStatuses.filterNot { status -> status.satisfied }
        return@withLock if (missing.isEmpty()) {
            publish(
                InstallerReport(
                    phase = InstallerPhase.READY,
                    stepsTotal = plan.steps.size,
                    stepsDone = plan.steps.size,
                    message = "instalación y verificación correctas (${finalStatuses.size} CLI(s))",
                    cliStatuses = finalStatuses,
                ),
            )
        } else {
            publish(
                InstallerReport(
                    phase = InstallerPhase.FAILED,
                    stepsTotal = plan.steps.size,
                    stepsDone = plan.steps.size,
                    message = "verificación falló: " +
                        missing.joinToString(", ") { status -> status.displayName },
                    cliStatuses = finalStatuses,
                ),
            )
        }
    }

    /**
     * Ejecuta los pasos del plan en orden. Devuelve `null` si todos
     * pasaron, o el reporte `FAILED` del primer fallo (sin ejecutar los
     * pasos restantes).
     */
    private suspend fun executeSteps(
        plan: InstallPlan,
        statuses: List<CliStatus>,
    ): InstallerReport? {
        var done = 0
        for (step in plan.steps) {
            _report.value = InstallerReport(
                phase = InstallerPhase.INSTALLING,
                stepsTotal = plan.steps.size,
                stepsDone = done,
                currentStepId = step.id,
                message = "ejecutando: ${step.description}",
                cliStatuses = statuses,
            )
            val result = try {
                runner.run(step.guestCommand, installTimeoutMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return InstallerReport(
                    phase = InstallerPhase.FAILED,
                    stepsTotal = plan.steps.size,
                    stepsDone = done + 1,
                    currentStepId = step.id,
                    message = "fallo en '${step.id}': ${error.describe()}",
                    cliStatuses = statuses,
                )
            }
            done++
            if (!result.isSuccess) {
                return InstallerReport(
                    phase = InstallerPhase.FAILED,
                    stepsTotal = plan.steps.size,
                    stepsDone = done,
                    currentStepId = step.id,
                    message = "fallo en '${step.id}': ${result.describe()}",
                    cliStatuses = statuses,
                )
            }
        }
        return null
    }

    /** Sondaea cada CLI del registro con su comando de verificación. */
    private suspend fun probeStatuses(): List<CliStatus> = specs.map { spec ->
        try {
            val result = runner.run(spec.verifyCommand(), verifyTimeoutMs)
            val output = result.stdout.ifBlank { result.stderr }
            val version = if (result.isSuccess) VersionDetector.parse(output) else null
            CliStatus(
                specId = spec.id,
                displayName = spec.displayName,
                installed = version != null,
                version = version,
                satisfied = spec.isSatisfiedBy(version),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Sandbox no arrancable o lanzador caído: el CLI se trata como
            // no instalado; el primer paso de instalación dará el motivo.
            CliStatus(
                specId = spec.id,
                displayName = spec.displayName,
                installed = false,
                version = null,
                satisfied = false,
            )
        }
    }

    private fun publish(report: InstallerReport): InstallerReport {
        _report.value = report
        return report
    }

    private fun GuestResult.describe(): String = when {
        timedOut -> "timeout tras $installTimeoutMs ms"
        else -> "exit $exitCode${stderr.detailSuffix()}"
    }

    private fun Exception.describe(): String = message ?: javaClass.simpleName

    private fun String.detailSuffix(): String {
        val detail = trim()
        return if (detail.isEmpty()) "" else " — $detail"
    }

    companion object {
        const val DEFAULT_VERIFY_TIMEOUT_MS = 30_000L
        const val DEFAULT_INSTALL_TIMEOUT_MS = 600_000L
    }
}
