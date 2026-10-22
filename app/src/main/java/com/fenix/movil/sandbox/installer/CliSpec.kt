// ============================================================================
// CliSpec.kt — descriptor de un CLI instalable en el sandbox (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.fenix.movil.sandbox.installer.InstallAction.Companion.BINARY_NAME
import com.fenix.movil.sandbox.installer.InstallAction.Companion.DEB_PACKAGE

/**
 * Descriptor inmutable de un CLI que el instalador (B5) puede detectar,
 * instalar y verificar dentro del rootfs.
 *
 * - [aptDependencies]: paquetes Debian previos necesarios (runtime de
 *   lenguaje, CAs, utilidades). Se **deduplican** en el plan en un único
 *   paso apt compartido.
 * - [actions]: acciones propias del CLI (p. ej. un paquete npm global).
 * - [minVersion]: piso opcional de versión; `null` = cualquier versión
 *   funcional sirve (política por defecto, `docs/sandbox-clis.md`).
 * - [verifyCommand]: comando que prueba que el binario existe y corre
 *   (por defecto `<binary> --version`).
 *
 * Los alfabetos de [id], [binary] y los nombres de paquete se validan en
 * el `init`: un registro malicioso o corrupto revienta al construirse, no
 * en medio de una instalación.
 */
data class CliSpec(
    /** Identificador estable del CLI (clave de reportes y UI). */
    val id: String,
    /** Nombre visible para el usuario. */
    val displayName: String,
    /** Binario dentro del guest (resuelto por el PATH del entorno aislado). */
    val binary: String,
    /** Paquetes apt previos requeridos (se fusionan en el paso `apt:base`). */
    val aptDependencies: List<String> = emptyList(),
    /** Acciones de instalación propias del CLI. */
    val actions: List<InstallAction> = emptyList(),
    /** Piso mínimo de versión; `null` = cualquiera funcional. */
    val minVersion: String? = null,
) {
    init {
        require(ID.matches(id)) { "id de CLI inválido (alfabeto kebab): '$id'" }
        require(displayName.isNotBlank()) { "displayName vacío para el CLI '$id'" }
        require(BINARY_NAME.matches(binary)) { "binario inválido para el CLI '$id': '$binary'" }
        aptDependencies.forEach { name ->
            require(DEB_PACKAGE.matches(name)) {
                "dependencia apt inválida en '$id': '$name'"
            }
        }
        require(actions.isNotEmpty()) {
            "el CLI '$id' no tiene acciones: no hay cómo instalarlo"
        }
        if (minVersion != null) {
            require(VersionDetector.parse(minVersion) == minVersion) {
                "minVersion inválida en '$id': '$minVersion' (esperado número con puntos)"
            }
        }
    }

    /** Comando que verifica el CLI dentro del guest. */
    fun verifyCommand(): List<String> = listOf(binary, "--version")

    /**
     * ¿[detectedVersion] satisface a este CLI? `null` (no instalado o
     * binario roto) nunca satisface.
     */
    fun isSatisfiedBy(detectedVersion: String?): Boolean =
        VersionDetector.satisfies(detectedVersion, minVersion)

    companion object {
        /** Alfabeto del id de un CLI. */
        val ID = Regex("[a-z0-9][a-z0-9-]*")

        /** Runtime base común a los agentes CLI (npm necesita Node + CAs). */
        private val AGENT_RUNTIME_DEPS = listOf(
            "ca-certificates",
            "curl",
            "git",
            "nodejs",
            "npm",
        )

        /**
         * Registro por defecto. Solo CLIs instalables con los gestores de
         * paquetes del propio rootfs (apt/npm): el código no incrusta URLs
         * de descarga (RN-06), así que las versiones exactas las decide la
         * cadena de suministro del rootfs y del registro npm.
         *
         * Verificado empíricamente contra el registro npm (2026-10-22):
         * `opencode-ai` → bin `opencode`; `@anthropic-ai/claude-code` →
         * bin `claude`; `@openai/codex` → bin `codex`.
         */
        fun defaultRegistry(): List<CliSpec> = listOf(OPENCODE, CLAUDE_CODE, CODEX_CLI)

        val OPENCODE = CliSpec(
            id = "opencode",
            displayName = "OpenCode",
            binary = "opencode",
            aptDependencies = AGENT_RUNTIME_DEPS,
            actions = listOf(InstallAction.NpmGlobalInstall(listOf("opencode-ai"))),
        )

        val CLAUDE_CODE = CliSpec(
            id = "claude-code",
            displayName = "Claude Code",
            binary = "claude",
            aptDependencies = AGENT_RUNTIME_DEPS,
            actions = listOf(InstallAction.NpmGlobalInstall(listOf("@anthropic-ai/claude-code"))),
        )

        val CODEX_CLI = CliSpec(
            id = "codex-cli",
            displayName = "Codex CLI",
            binary = "codex",
            aptDependencies = AGENT_RUNTIME_DEPS,
            actions = listOf(InstallAction.NpmGlobalInstall(listOf("@openai/codex"))),
        )
    }
}
