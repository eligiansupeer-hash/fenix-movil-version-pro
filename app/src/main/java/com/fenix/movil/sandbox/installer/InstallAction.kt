// ============================================================================
// InstallAction.kt — acciones de instalación y su receta shell (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import com.fenix.movil.sandbox.SandboxConfig

/**
 * Acción de instalación declarativa, ejecutable **dentro** del guest.
 *
 * Cada acción se materializa como un comando guest determinista
 * (`/bin/bash -c <receta>`); el plan (B5) decide el orden y el orquestador
 * la ejecuta paso a paso con acuse de éxito/fallo.
 *
 * **Defensa de inyección**: los nombres de paquete se interpolan en una
 * receta shell, por lo que se validan contra un alfabeto estricto en el
 * `init` (rechazo ruidoso, no sanitización silenciosa). Un nombre de
 * paquete nunca puede romper fuera de su token.
 */
sealed interface InstallAction {
    /** Descripción corta para logs/UI. */
    val description: String

    /** Receta shell determinista de la acción. */
    fun script(): String

    /** Comando guest que ejecuta la receta (`bash -c`). */
    fun guestCommand(): List<String> = listOf(SandboxConfig.DEFAULT_SHELL, "-c", script())

    /**
     * Instalación vía `apt-get` con los repositorios **del propio rootfs**
     * (B2): el código no incrusta URLs (RN-06); la política de mirrors es
     * dato del rootfs, no del APK.
     */
    data class AptInstall(val packages: List<String>) : InstallAction {
        init {
            require(packages.isNotEmpty()) { "AptInstall sin paquetes" }
            packages.forEach { name ->
                require(DEB_PACKAGE.matches(name)) {
                    "nombre de paquete apt inválido (alfabeto Debian): '$name'"
                }
            }
        }

        override val description: String
            get() = "apt-get install ${packages.joinToString(" ")}"

        override fun script(): String = listOf(
            "set -euo pipefail",
            "export DEBIAN_FRONTEND=noninteractive",
            "apt-get update -y",
            "apt-get install -y --no-install-recommends ${packages.joinToString(" ")}",
            "rm -rf /var/lib/apt/lists/*",
        ).joinToString(separator = "\n")
    }

    /**
     * Instalación de paquetes npm globales (agentes CLI publicados como
     * paquetes npm). El registro npm lo resuelve el propio guest.
     */
    data class NpmGlobalInstall(val packages: List<String>) : InstallAction {
        init {
            require(packages.isNotEmpty()) { "NpmGlobalInstall sin paquetes" }
            packages.forEach { name ->
                require(NPM_PACKAGE.matches(name)) {
                    "nombre de paquete npm inválido (alfabeto npm, scoped permitido): '$name'"
                }
            }
        }

        override val description: String
            get() = "npm install -g ${packages.joinToString(" ")}"

        override fun script(): String = listOf(
            "set -euo pipefail",
            "npm install -g --no-audit --no-fund ${packages.joinToString(" ")}",
        ).joinToString(separator = "\n")
    }

    companion object {
        /** Alfabeto de nombres de paquete Debian (documentación de dpkg). */
        val DEB_PACKAGE = Regex("[a-z0-9][a-z0-9+.-]*")

        /** Alfabeto de nombres npm (con paquetes scoped `@org/name`). */
        val NPM_PACKAGE = Regex("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")

        /** Alfabeto de binarios dentro del guest. */
        val BINARY_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
    }
}
