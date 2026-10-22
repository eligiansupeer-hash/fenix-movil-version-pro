// ============================================================================
// NetworkProbe.kt — detección de red (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Puerto de detección de conectividad: el instalador solo pide "¿hay red
 * usable?" sin depender de Android, para que las pruebas usen un doble.
 */
fun interface NetworkProbe {
    /** `true` si hay una red con acceso a Internet **validado**. */
    fun isOnline(): Boolean
}

/**
 * Implementación real sobre [ConnectivityManager].
 *
 * Exige `NET_CAPABILITY_VALIDATED`: una Wi-Fi local sin salida a Internet
 * (café, portal cautivo) reporta `false`, de modo que el instalador no
 * intenta `apt-get update` contra un enlace muerto. Requiere
 * `ACCESS_NETWORK_STATE` (ya declarado en el manifest, B3).
 */
class AndroidNetworkProbe(context: Context) : NetworkProbe {
    private val connectivity: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isOnline(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
