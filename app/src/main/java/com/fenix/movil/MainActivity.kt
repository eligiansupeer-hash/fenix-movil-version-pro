// ============================================================================
// MainActivity.kt — Actividad única (one-activity, navegación Compose)
// ============================================================================

package com.fenix.movil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.fenix.movil.ui.theme.FenixTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Actividad única. El NavHost + BottomBar de 5 pestañas llega en D1;
 * aquí se monta el tema y el contenedor raíz.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FenixTheme {
                FenixRootContainer()
            }
        }
    }
}

/** Contenedor raíz provisional (lo reemplaza MainScreen en D1). */
@Composable
private fun FenixRootContainer() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Fénix Móvil")
        }
    }
}
