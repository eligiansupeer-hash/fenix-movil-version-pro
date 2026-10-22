// ============================================================================
// TerminalChunk.kt — unidad de salida decodificada de una sesión (plan B4)
// ============================================================================

package com.fenix.movil.terminal

/** Stream de origen de un chunk. */
enum class TerminalStream {
    /**
     * Salida de la sesión hacia la terminal. En modo PTY el `stderr` del
     * guest **se fusiona aquí** (semántica de tty: todo lo escribe el
     * mismo terminal), de modo que un `echo x >&2` del shell aparece como
     * STDOUT. El stream STDERR del transporte conserva los diagnósticos
     * del puente/proot (fuera de la PTY).
     */
    STDOUT,

    /** `stderr` del transporte (diagnósticos del puente, proot, avisos). */
    STDERR,
}

/** Clase de contenido de un chunk. */
enum class TerminalChunkKind {
    /** Texto literal ya decodificado en UTF-8 (se muestra tal cual). */
    TEXT,

    /**
     * Secuencia de escape ANSI **cruda** (CSI/OSC/ESC) conservada byte a
     * byte. La vista VT (D3) la interpreta; [ShellSession.plainText] la
     * elimina del scrollback.
     */
    ESCAPE,
}

/**
 * Trozo de salida de una sesión: texto decodificado + su clasificación.
 *
 * Los chunks se emiten en orden de llegada por stream; un byte UTF-8
 * partido entre dos lecturas nunca aparece a medias (el decodificador
 * incremental lo recompone) y una secuencia de escape partida entre dos
 * lecturas se emite **una sola vez**, completa, cuando llega su byte final.
 */
data class TerminalChunk(
    val stream: TerminalStream,
    val kind: TerminalChunkKind,
    val text: String,
)
