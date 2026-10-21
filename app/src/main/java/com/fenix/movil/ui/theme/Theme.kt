// ============================================================================
// Theme.kt — Tema Material 3 dark-only de Fénix Móvil
// Autoridad: docs/Diseno_penpot.md — la app es exclusivamente dark
// (solo existe paleta oscura en los 28 tableros Penpot).
// Tokens: 8 base (superficie ×3, fuego ×2, oro, verde, azul) + extras.
// ============================================================================

package com.fenix.movil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colores operativos fuera del esquema Material estándar
 * (grabación, micrófono, banner crítico, canvases específicos de tablero).
 */
@Immutable
data class FenixExtraColors(
    val recording: Color = FenixRecordingGreen,
    val micRest: Color = FenixMicRestBlue,
    val crimson: Color = FenixCrimson,
    val docsCanvas: Color = FenixDocsCanvas,
    val studioNavy: Color = FenixStudioNavy,
    val solarGoldDeep: Color = FenixSolarGoldDeep,
)

private val FenixDarkColors = darkColorScheme(
    // Núcleo: fondo de carbón + acento magma
    primary = FenixFireCore,
    onPrimary = FenixTextPrimary,
    primaryContainer = FenixFireCore,
    onPrimaryContainer = FenixTextPrimary,
    // Secundario: fuego plasma
    secondary = FenixFirePlasma,
    onSecondary = FenixTextPrimary,
    secondaryContainer = FenixSurfaceCard,
    onSecondaryContainer = FenixTextPrimarySoft,
    // Terciario: oro solar (indicadores activos / botón enviar)
    tertiary = FenixSolarGold,
    onTertiary = FenixSurfaceBase,
    tertiaryContainer = FenixSurfaceCard,
    onTertiaryContainer = FenixSolarGold,
    // Superficies (jerarquía Penpot)
    background = FenixSurfaceBase,
    onBackground = FenixTextPrimary,
    surface = FenixSurfaceBar,
    onSurface = FenixTextPrimary,
    surfaceVariant = FenixSurfaceCard,
    onSurfaceVariant = FenixTextSecondary,
    surfaceContainer = FenixSurfaceCard,
    surfaceContainerHigh = FenixSurfaceBar,
    surfaceContainerHighest = FenixSurfaceBase,
    surfaceContainerLow = FenixSurfaceBar,
    surfaceContainerLowest = FenixSurfaceBase,
    // Errores / banners críticos
    error = FenixCrimson,
    onError = FenixTextPrimary,
    errorContainer = FenixCrimson,
    onErrorContainer = FenixTextPrimary,
    // Bordes y placeholder
    outline = FenixTextSecondary,
    outlineVariant = FenixSurfaceCard,
    inverseSurface = FenixTextPrimary,
    inverseOnSurface = FenixSurfaceBase,
    inversePrimary = FenixFirePlasma,
)

/** Colores operativos accesibles desde cualquier composable. */
val FenixExtras: FenixExtraColors = FenixExtraColors()

/**
 * Tema Fénix Móvil. Dark-only por diseño (los 28 tableros Penpot solo
 * definen paleta oscura); no existe variante light.
 */
@Composable
fun FenixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FenixDarkColors,
        typography = FenixTypography,
        content = content,
    )
}
