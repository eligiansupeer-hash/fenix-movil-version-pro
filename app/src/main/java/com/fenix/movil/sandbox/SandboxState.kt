// ============================================================================
// SandboxState.kt — estados del ciclo de vida del sandbox (plan B3)
// ============================================================================

package com.fenix.movil.sandbox

/**
 * Estados del ciclo de vida del sandbox, expuestos como `StateFlow` por
 * [SandboxManager] (plan B3).
 *
 * Transiciones garantizadas:
 * ```
 * STOPPED --start()--> BOOTING --gracia--> RUNNING
 *    ^                   |                  |
 *    |                   v                  v
 *    +------stop()---- ERROR <---salida inesperada---+
 * ```
 */
enum class SandboxState {
    /** Sin proceso lanzado: estado inicial y destino de [SandboxManager.stop]. */
    STOPPED,

    /** Arranque en curso: proceso lanzado y dentro de la ventana de gracia. */
    BOOTING,

    /** Proceso vivo y superada la ventana de gracia: sandbox operativo. */
    RUNNING,

    /** Fallo de valididad, fallo de arranque o salida inesperada del proceso. */
    ERROR,
}
