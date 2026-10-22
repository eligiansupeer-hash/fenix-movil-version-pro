// ============================================================================
// SandboxPaths.kt — rutas físicas del sandbox derivadas del Context
// ============================================================================

package com.fenix.movil.sandbox

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Rutas y datos del sandbox resueltos desde el `Context` de la app
 * (autoridad: `docs/arquitectura.md` §3).
 *
 * - proot → `applicationInfo.nativeLibraryDir/libproot.so`: directorio de
 *   sólo lectura que Android marca como ejecutable (`apk_data_file`), único
 *   lugar desde el que `execve` pasa el chequeo W^X de Android 10+.
 * - rootfs → `noBackupFilesDir/sandbox/debian-12`: fuera del backup a la
 *   nube (≈350 MB descomprimidos no deben subirse masivamente).
 * - [sharedDir] → carpeta de la app montada como `/fenix` en el guest para
 *   intercambiar archivos host ↔ sandbox (F-Code/F-Docs/descargas). Mismo
 *   UID que la app: no amplía privilegios, sólo da una ruta estable.
 */
data class SandboxPaths(
    val prootBinary: File,
    val rootfsDir: File,
    val sharedDir: File,
    val dnsServers: List<String>,
) {
    /** Configuración de arranque derivada de estas rutas. */
    fun toConfig(): SandboxConfig = SandboxConfig(
        prootBinary = prootBinary,
        rootfsDir = rootfsDir,
        binds = SandboxConfig.defaultBinds(
            listOf(BindMount(sharedDir.absolutePath, GUEST_SHARED_DIR)),
        ),
        dnsServers = dnsServers,
    )

    companion object {
        const val PROOT_LIB_NAME = "libproot.so"
        const val ROOTFS_RELATIVE = "sandbox/debian-12"
        const val SHARED_DIR_NAME = "sandbox-share"
        const val GUEST_SHARED_DIR = "/fenix"

        /** Resuelve rutas y DNS del dispositivo a partir del `Context`. */
        fun from(context: Context): SandboxPaths {
            val sharedDir = File(context.filesDir, SHARED_DIR_NAME)
            sharedDir.mkdirs()
            return SandboxPaths(
                prootBinary = File(context.applicationInfo.nativeLibraryDir, PROOT_LIB_NAME),
                rootfsDir = File(context.noBackupFilesDir, ROOTFS_RELATIVE),
                sharedDir = sharedDir,
                dnsServers = deviceDnsServers(context),
            )
        }

        /**
         * DNS activos del dispositivo. bionic no publica `/etc/resolv.conf`
         * (la resolución la hace netd), así que se leen de `LinkProperties`
         * para volcarlos dentro del rootfs. Requiere `ACCESS_NETWORK_STATE`
         * (ya declarado en el manifest); sin red/permiso devuelve vacío.
         */
        private fun deviceDnsServers(context: Context): List<String> {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            val link = connectivity.getLinkProperties(network) ?: return emptyList()
            return link.dnsServers
                .mapNotNull { address -> address.hostAddress }
                .filter { address -> address.isNotBlank() }
        }
    }
}
