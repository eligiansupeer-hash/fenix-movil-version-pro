// ============================================================================
// Color.kt — Design tokens de color
// Autoridad: docs/Diseno_penpot.md §1 (PALETA DE COLORES Y DESIGN TOKENS)
// Base 412×915 dp. Cada constante es el token literal del documento vivo.
// NO renombrar sin actualizar el documento de especificación.
// ============================================================================

package com.fenix.movil.ui.theme

import androidx.compose.ui.graphics.Color

// --- Superficies (glassmorphism oscuro) ---
/** Superficie base (fondo de pantalla) — negro carbón profundo. */
const val TokenSurfaceBase = 0xFF0A0B10

/** Superficie TopAppBar / BottomBar — vidrio ahumado oscuro. */
const val TokenSurfaceBar = 0xFF0E0F17

/** Superficie de tarjetas Bento / Input Pill — dark glassmorphism. */
const val TokenSurfaceCard = 0xFF161722

// --- Acentos Fénix (fuego) ---
/** Acento Fénix fuego núcleo — rojo magma incandescente. */
const val TokenFireCore = 0xFFFF3D00

/** Acento Fénix fuego plasma — naranja fuego. */
const val TokenFirePlasma = 0xFFFF9100

// --- Oro solar (indicadores activos) ---
/** Acento solar activo / indicadores — oro solar. */
const val TokenSolarGold = 0xFFFFD54F

/** Oro solar profundo (segundo tono de indicador). */
const val TokenSolarGoldDeep = 0xFFFFD700

// --- Estados reactivos ---
/** Estado de grabación de audio — verde esmeralda reactivo. */
const val TokenRecordingGreen = 0xFF00E676

/** Estado de micrófono en reposo — azul celeste. */
const val TokenMicRestBlue = 0xFF40C4FF

// --- Texto ---
const val TokenTextPrimary = 0xFFFFFFFF
const val TokenTextPrimarySoft = 0xFFF0F0F5
const val TokenTextSecondary = 0xFF8C8C9E
const val TokenTextPlaceholder = 0xFF9E9EB2

// --- Extras documentados en tableros (Diseno_penpot.md §3) ---
/** Banner crítico perimetral (tableros 10 y 28) — carmesí. */
const val TokenCrimson = 0xFFFF1744

/** Canvas de F-Docs / Conectores / Ayuda / Creaciones (tableros 24, 26–28). */
const val TokenDocsCanvas = 0xFF090A0F

/** Canvas Azul Noche Institucional de Fénix Studio (tablero 25). */
const val TokenStudioNavy = 0xFF0B0E1B

// --- Material ColorScheme (valores ya resueltos a Color) ---
val FenixSurfaceBase = Color(TokenSurfaceBase)
val FenixSurfaceBar = Color(TokenSurfaceBar)
val FenixSurfaceCard = Color(TokenSurfaceCard)
val FenixFireCore = Color(TokenFireCore)
val FenixFirePlasma = Color(TokenFirePlasma)
val FenixSolarGold = Color(TokenSolarGold)
val FenixSolarGoldDeep = Color(TokenSolarGoldDeep)
val FenixRecordingGreen = Color(TokenRecordingGreen)
val FenixMicRestBlue = Color(TokenMicRestBlue)
val FenixTextPrimary = Color(TokenTextPrimary)
val FenixTextPrimarySoft = Color(TokenTextPrimarySoft)
val FenixTextSecondary = Color(TokenTextSecondary)
val FenixTextPlaceholder = Color(TokenTextPlaceholder)
val FenixCrimson = Color(TokenCrimson)
val FenixDocsCanvas = Color(TokenDocsCanvas)
val FenixStudioNavy = Color(TokenStudioNavy)
