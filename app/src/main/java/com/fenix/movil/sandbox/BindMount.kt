// ============================================================================
// BindMount.kt — montaje host → guest para proot (`-b host[:guest]`)
// ============================================================================

package com.fenix.movil.sandbox

/**
 * Un montaje de proot: hace visible [hostPath] (del sistema anfitrión)
 * dentro del rootfs, opcionalmente en [guestPath].
 *
 * Reglas de seguridad (aplicadas en `init`, fallan ruidosamente al construir):
 * - ambas rutas deben ser **absolutas**: un bind relativo se resolvería
 *   contra el cwd del proceso y abriría rutas arbitrarias dentro del guest;
 * - [guestPath], si existe, no puede contener `:` (separador de proot).
 *
 * Ejemplos de argv resultante: `/dev` → `-b /dev`;
 * `/data/data/<pkg>/files/sandbox-share` → `-b <...>/sandbox-share:/fenix`.
 */
data class BindMount(
    val hostPath: String,
    val guestPath: String? = null,
) {
    init {
        require(hostPath.startsWith("/")) {
            "ruta host de bind debe ser absoluta: '$hostPath'"
        }
        require(guestPath == null || guestPath.startsWith("/")) {
            "ruta guest de bind debe ser absoluta: '$guestPath'"
        }
        require(guestPath == null || !guestPath.contains(':')) {
            "ruta guest de bind no puede contener ':': '$guestPath'"
        }
    }

    /** Argumento de proot para este montaje (`host` o `host:guest`). */
    fun toArgument(): String =
        if (guestPath == null) hostPath else "$hostPath:$guestPath"
}
