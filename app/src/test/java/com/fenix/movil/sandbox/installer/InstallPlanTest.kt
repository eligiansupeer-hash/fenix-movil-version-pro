// ============================================================================
// InstallPlanTest.kt — plan determinista de instalación (plan B5)
//
// Cobertura: validación de specs (alfabetos anti-inyección), orden y
// deduplicación de pasos, idempotencia (specs satisfechos no generan
// pasos), determinismo (registro completo vs. subset), requiresNetwork y
// recetas shell exactas de apt/npm.
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallPlanTest {
    // ------------------------------------------------------------------
    // CliSpec: validación en construcción
    // ------------------------------------------------------------------

    @Test
    fun `el registro por defecto tiene opencode claude y codex`() {
        val ids = CliSpec.defaultRegistry().map { spec -> spec.id }

        assertThat(ids).containsExactly("opencode", "claude-code", "codex-cli").inOrder()
    }

    @Test
    fun `el registro por defecto usa npm globales y un runtime apt comun`() {
        CliSpec.defaultRegistry().forEach { spec ->
            val apt = spec.actions.filterIsInstance<InstallAction.NpmGlobalInstall>()
            assertThat(apt).hasSize(1)
            assertThat(spec.aptDependencies).containsAtLeast("nodejs", "npm", "git")
        }
    }

    @Test
    fun `un id fuera del alfabeto kebab se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliSpec(
                id = "Open Code!",
                displayName = "x",
                binary = "opencode",
                actions = listOf(InstallAction.NpmGlobalInstall(listOf("opencode-ai"))),
            )
        }
    }

    @Test
    fun `un binario con caracteres invalidos se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliSpec(
                id = "x",
                displayName = "x",
                binary = "bin; rm -rf /",
                actions = listOf(InstallAction.NpmGlobalInstall(listOf("x"))),
            )
        }
    }

    @Test
    fun `un CLI sin acciones no es instalable y se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliSpec(id = "x", displayName = "x", binary = "x")
        }
    }

    @Test
    fun `dependencias apt con metacaracteros se rechazan`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallAction.AptInstall(listOf("ca-certificates; curl evil"))
        }
    }

    @Test
    fun `paquetes npm con metacaracteros se rechazan`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallAction.NpmGlobalInstall(listOf("pkg; curl evil"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstallAction.NpmGlobalInstall(listOf("$(id)"))
        }
    }

    @Test
    fun `paquetes npm scoped son validos`() {
        val action = InstallAction.NpmGlobalInstall(listOf("@anthropic-ai/claude-code"))

        assertThat(action.packages).containsExactly("@anthropic-ai/claude-code")
    }

    @Test
    fun `una minVersion no numerica se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            CliSpec(
                id = "x",
                displayName = "x",
                binary = "x",
                actions = listOf(InstallAction.NpmGlobalInstall(listOf("x"))),
                minVersion = "latest",
            )
        }
    }

    // ------------------------------------------------------------------
    // Recetas shell
    // ------------------------------------------------------------------

    @Test
    fun `la receta apt es determinista e idempotente`() {
        val script = InstallAction.AptInstall(listOf("git", "nodejs")).script()

        assertThat(script).isEqualTo(
            listOf(
                "set -euo pipefail",
                "export DEBIAN_FRONTEND=noninteractive",
                "apt-get update -y",
                "apt-get install -y --no-install-recommends git nodejs",
                "rm -rf /var/lib/apt/lists/*",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `la receta npm es determinista y global`() {
        val script = InstallAction.NpmGlobalInstall(listOf("opencode-ai")).script()

        assertThat(script).isEqualTo(
            listOf(
                "set -euo pipefail",
                "npm install -g --no-audit --no-fund opencode-ai",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `el comando guest envuelve la receta en bash del rootfs`() {
        val action = InstallAction.AptInstall(listOf("git"))
        val command = action.guestCommand()

        assertThat(command).isEqualTo(
            listOf(com.fenix.movil.sandbox.SandboxConfig.DEFAULT_SHELL, "-c", action.script()),
        )
    }

    // ------------------------------------------------------------------
    // Build del plan
    // ------------------------------------------------------------------

    @Test
    fun `un registro vacio o con ids repetidos se rechaza`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstallPlan.build(emptyList(), emptyMap())
        }
        val duplicated = listOf(CliSpec.OPENCODE, CliSpec.OPENCODE)
        assertThrows(IllegalArgumentException::class.java) {
            InstallPlan.build(duplicated, emptyMap())
        }
    }

    @Test
    fun `con todo instalado no hay pasos ni red`() {
        val detected = mapOf(
            "opencode" to "1.18.21",
            "claude-code" to "2.0.1",
            "codex-cli" to "0.9.0",
        )
        val plan = InstallPlan.build(CliSpec.defaultRegistry(), detected)

        assertThat(plan.steps).isEmpty()
        assertThat(plan.requiresNetwork).isFalse()
        assertThat(plan.pendingSpecs).isEmpty()
        assertThat(plan.satisfiedSpecs)
            .containsExactly("opencode", "claude-code", "codex-cli")
    }

    @Test
    fun `con nada instalado el orden es apt base y luego npm por registro`() {
        val plan = InstallPlan.build(CliSpec.defaultRegistry(), emptyMap())

        assertThat(plan.steps.map { step -> step.id }).containsExactly(
            InstallPlan.APT_STEP_ID,
            "npm-global:opencode",
            "npm-global:claude-code",
            "npm-global:codex-cli",
        ).inOrder()
        assertThat(plan.requiresNetwork).isTrue()
        assertThat(plan.pendingSpecs).containsExactly(
            "opencode",
            "claude-code",
            "codex-cli",
        ).inOrder()
    }

    @Test
    fun `el paso apt base fusiona y deduplica dependencias`() {
        val plan = InstallPlan.build(CliSpec.defaultRegistry(), emptyMap())
        val aptStep = plan.steps.first()

        assertThat(aptStep.id).isEqualTo(InstallPlan.APT_STEP_ID)
        // El paso es `bash -c <receta>`; la receta fusiona y deduplica.
        val recipe = aptStep.guestCommand.last()
        val installLine = recipe.lineSequence().first { it.contains("apt-get install") }
        assertThat(installLine).isEqualTo(
            "apt-get install -y --no-install-recommends " +
                "ca-certificates curl git nodejs npm",
        )
    }

    @Test
    fun `la planificacion es idempotente solo instala lo que falta`() {
        val detected = mapOf("opencode" to "1.18.21")
        val plan = InstallPlan.build(CliSpec.defaultRegistry(), detected)

        assertThat(plan.steps.map { step -> step.id }).containsExactly(
            InstallPlan.APT_STEP_ID,
            "npm-global:claude-code",
            "npm-global:codex-cli",
        ).inOrder()
        assertThat(plan.satisfiedSpecs).containsExactly("opencode")
        assertThat(plan.pendingSpecs).containsExactly("claude-code", "codex-cli")
    }

    @Test
    fun `una version bajo el minimo cuenta como pendiente`() {
        val spec = CliSpec(
            id = "herramienta",
            displayName = "Herramienta",
            binary = "herramienta",
            actions = listOf(InstallAction.NpmGlobalInstall(listOf("herramienta"))),
            minVersion = "2.0.0",
        )
        val plan = InstallPlan.build(listOf(spec), mapOf("herramienta" to "1.9.9"))

        assertThat(plan.pendingSpecs).containsExactly("herramienta")
    }

    @Test
    fun `la planificacion es determinista para el mismo registro`() {
        val first = InstallPlan.build(CliSpec.defaultRegistry(), emptyMap())
        val second = InstallPlan.build(CliSpec.defaultRegistry(), emptyMap())

        assertThat(first.steps).isEqualTo(second.steps)
    }

    @Test
    fun `un subset del registro solo planifica ese subset`() {
        val subset = listOf(CliSpec.OPENCODE)
        val plan = InstallPlan.build(subset, emptyMap())

        assertThat(plan.steps.map { step -> step.id }).containsExactly(
            InstallPlan.APT_STEP_ID,
            "npm-global:opencode",
        ).inOrder()
    }
}
