// ============================================================================
// InstallPlan.kt — plan determinista de instalación de CLIs (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

/**
 * Paso concreto del plan: una acción traducida a comando guest, en orden
 * de ejecución.
 */
data class PlannedStep(
    /** Id estable del paso (`apt:base`, `npm-global:<cli>`). */
    val id: String,
    /** CLI responsable del paso; `null` para el paso apt compartido. */
    val specId: String?,
    /** Descripción corta para UI/logs. */
    val description: String,
    /** Comando guest exacto que ejecuta el paso. */
    val guestCommand: List<String>,
)

/**
 * Plan de instalación: **qué** instalar y **en qué orden**, derivado del
 * registro de CLIs y de las versiones ya detectadas en el guest.
 *
 * Reglas deterministas (testeadas en `InstallPlanTest`):
 *
 * 1. Un CLI está *pendiente* si la versión detectada no lo satisface
 *    (`CliSpec.isSatisfiedBy`).
 * 2. Los paquetes apt de los CLIs pendientes (dependencias + acciones
 *    `AptInstall`) se **deduplican** en un único paso `apt:base`
 *    al frente, en orden de primera aparición.
 * 3. Cada CLI pendiente aporta sus acciones no-apt como paso propio
 *    (`npm-global:<id>`), en orden del registro.
 * 4. La verificación de versiones **no** es un paso del plan: la orquesta
 *    el [CliInstaller] en sus fases `CHECKING` (antes) y `VERIFYING`
 *    (después), para que el plan describa solo instalación.
 * 5. [requiresNetwork] es `true` solo si hay CLIs pendientes: con todo
 *    instalado no se necesita red.
 *
 * El plan nunca contiene datos sensibles ni valores fijos de red: los
 * comandos usan los gestores de paquetes del propio rootfs (RN-06).
 */
class InstallPlan private constructor(
    /** Pasos de instalación, en orden de ejecución. */
    val steps: List<PlannedStep>,
    /** Ids de CLIs ya satisfechos (sin pasos propios). */
    val satisfiedSpecs: List<String>,
    /** Ids de CLIs pendientes de instalación. */
    val pendingSpecs: List<String>,
) {
    /** ¿El plan necesita red para ejecutarse? */
    val requiresNetwork: Boolean
        get() = pendingSpecs.isNotEmpty()

    companion object {
        /**
         * Construye el plan para [specs] con las versiones ya conocidas
         * ([detected]: id de CLI → versión detectada o `null`).
         *
         * @throws IllegalArgumentException si el registro es inválido
         *   (vacío o con ids duplicados): los specs ya validaron sus
         *   campos individuales al construirse.
         */
        fun build(specs: List<CliSpec>, detected: Map<String, String?>): InstallPlan {
            require(specs.isNotEmpty()) { "registro de CLIs vacío" }
            val ids = specs.map { spec -> spec.id }
            require(ids.distinct().size == ids.size) {
                "ids de CLI duplicados en el registro: $ids"
            }

            val pending = specs.filterNot { spec -> spec.isSatisfiedBy(detected[spec.id]) }
            val satisfiedIds = specs
                .filter { spec -> spec.isSatisfiedBy(detected[spec.id]) }
                .map { spec -> spec.id }

            val steps = mutableListOf<PlannedStep>()

            // Paso apt compartido: dependencias + acciones apt de los pendientes.
            val aptPackages = linkedSetOf<String>()
            pending.forEach { spec ->
                aptPackages += spec.aptDependencies
                spec.actions
                    .filterIsInstance<InstallAction.AptInstall>()
                    .forEach { action -> aptPackages += action.packages }
            }
            if (aptPackages.isNotEmpty()) {
                val action = InstallAction.AptInstall(aptPackages.toList())
                steps += PlannedStep(
                    id = APT_STEP_ID,
                    specId = null,
                    description = "paquetes base del sistema (apt)",
                    guestCommand = action.guestCommand(),
                )
            }

            // Pasos propios de cada CLI pendiente, en orden del registro.
            pending.forEach { spec ->
                spec.actions
                    .filterNot { action -> action is InstallAction.AptInstall }
                    .forEach { action ->
                        steps += PlannedStep(
                            id = "npm-global:${spec.id}",
                            specId = spec.id,
                            description = "${action.description} (${spec.displayName})",
                            guestCommand = action.guestCommand(),
                        )
                    }
            }

            return InstallPlan(
                steps = steps,
                satisfiedSpecs = satisfiedIds,
                pendingSpecs = pending.map { spec -> spec.id },
            )
        }

        /** Id del paso apt compartido. */
        const val APT_STEP_ID = "apt:base"
    }
}
