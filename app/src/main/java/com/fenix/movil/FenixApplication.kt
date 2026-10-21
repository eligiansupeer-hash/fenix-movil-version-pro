// ============================================================================
// FenixApplication.kt — Punto de entrada de la app (Hilt)
// ============================================================================

package com.fenix.movil

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application global. [HiltAndroidApp] genera el grafo de dependencias
 * usado por toda la app (sandbox, servidor MCP, repositorios de datos).
 */
@HiltAndroidApp
class FenixApplication : Application()
