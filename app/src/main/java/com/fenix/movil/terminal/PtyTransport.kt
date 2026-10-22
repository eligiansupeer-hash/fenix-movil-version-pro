// ============================================================================
// PtyTransport.kt — puerto del lado maestro de la sesión (plan B4)
// ============================================================================

package com.fenix.movil.terminal

import java.io.InputStream
import java.io.OutputStream

/**
 * Transporte de una sesión de shell: el lado maestro del pseudo-terminal.
 *
 * [ShellSession] es agnóstico de la implementación; el puente real
 * (`sandbox/BridgePtyTransport`, sobre el proceso proot + la PTY que vive
 * dentro del guest) implementa este puerto en B4. El mismo contrato sirve
 * para dobles de prueba y para cualquier transporte futuro.
 *
 * Los tres streams del enunciado de B4:
 * - [stdin]  → lo que escribe el usuario (teclado/programas).
 * - [stdout] → salida del guest hacia la terminal (en PTY incluye el
 *   `stderr` del guest: es la semántica real de un tty).
 * - [stderr] → diagnósticos **fuera** de la PTY (puente, proot). Nunca
 *   `null` en los transportes reales; consumirlo evita que la tubería de
 *   64 KB se llene y bloquee el proceso si nadie lo lee.
 */
interface PtyTransport {
    /**
     * `true` cuando la salida tiene semántica de tty: la disciplina de
     * línea mapea CR→NL, el eco lo hace el kernel y la redimensión llega
     * al proceso como `SIGWINCH`. Con `isTty = false` (tuberías crudas)
     * [ShellSession.sendLine] debe emitir `\n` en lugar de `\r`.
     */
    val isTty: Boolean

    /** Flujo de entrada de la sesión (stdin del proceso hijo). */
    val stdin: OutputStream

    /** Salida de la sesión (stdout del proceso hijo). */
    val stdout: InputStream

    /** Diagnósticos fuera de la PTY; `null` sólo si el transporte no los expone. */
    val stderr: InputStream?

    /**
     * Propaga la ventana al hijo. Devuelve `true` cuando la geometría
     * quedó **aplicada** en el tty del guest (el puente lo confirma con el
     * acuse del canal de control); `false` cuando no hay tty (tuberías)
     * o el canal no respondió — la llamada es reintentable.
     */
    fun resize(size: TerminalSize): Boolean

    /**
     * Cierra la sesión.
     *
     * @param terminate `true` en un cierre deliberado (la UI cerró la
     *   terminal): además de cerrar streams se termina al proceso hijo.
     *   `false` cuando el cierre lo originó el propio proceso (EOF en
     *   stdout): el hijo ya se está yendo y terminarlo a medias podría
     *   alterar su código de salida, así que sólo se limpia el canal.
     */
    fun close(terminate: Boolean)
}
