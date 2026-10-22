// ============================================================================
// InstallState.kt — estados y reporte del instalador de CLIs (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

/**
 * Fases del [CliInstaller].
 *
 * ```
 * IDLE → CHECKING ─┬─(todo instalado)──────────────→ READY
 *                  ├─(falta instalar y sin red)────→ NO_NETWORK
 *                  └─(falta instalar y hay red)────→ INSTALLING → VERIFYING
 *                                                       ├─→ READY
 *                                                       └─→ FAILED
 * ```
 */
enum class InstallerPhase {
    /** Nunca ejecutado. */
    IDLE,

    /** Sondeo de versiones de los CLIs del registro (`CHECKING`). */
    CHECKING,

    /** Faltan CLIs por instalar y no hay red usable. */
    NO_NETWORK,

    /** Ejecutando pasos del plan (`INSTALLING`). */
    INSTALLING,

    /** Verificación final de versiones (`VERIFYING`). */
    VERIFYING,

    /** Todos los CLIs del registro satisfechos. */
    READY,

    /** Un paso falló o la verificación final detectó un CLI insatisfecho. */
    FAILED,
}

/**
 * Estado de un CLI del registro tras la última comprobación.
 *
 * @param installed el binario existe y responde (versión parseable).
 * @param version versión detectada; `null` si no instalado o binario roto.
 * @param satisfied [installed] y, si el spec lo exige, [CliSpec.minVersion]
 *   cumplido.
 */
data class CliStatus(
    val specId: String,
    val displayName: String,
    val installed: Boolean,
    val version: String?,
    val satisfied: Boolean,
)

/**
 * Reporte inmutable del instalador, expuesto como `StateFlow` para que la
 * UI (y las pruebas) observen progreso y resultado.
 */
data class InstallerReport(
    val phase: InstallerPhase = InstallerPhase.IDLE,
    /** Pasos del plan (0 si no hubo plan que ejecutar). */
    val stepsTotal: Int = 0,
    /** Pasos completados hasta ahora. */
    val stepsDone: Int = 0,
    /** Id del paso en curso (`apt:base`, `npm-global:<cli>`); `null` fuera. */
    val currentStepId: String? = null,
    /** Mensaje corto para UI/logs. */
    val message: String? = null,
    /** Estado por CLI al final (o en el último sondeo). */
    val cliStatuses: List<CliStatus> = emptyList(),
) {
    /** `true` en las fases terminales. */
    val isFinished: Boolean
        get() = when (phase) {
            InstallerPhase.READY, InstallerPhase.FAILED, InstallerPhase.NO_NETWORK -> true
            else -> false
        }
}
