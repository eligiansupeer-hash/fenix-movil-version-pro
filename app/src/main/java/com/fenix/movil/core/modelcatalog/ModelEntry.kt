// ============================================================================
// ModelEntry.kt — entrada del catálogo de modelos (F1 del pipeline 5 filtros)
// ============================================================================

package com.fenix.movil.core.modelcatalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Entrada inmutable de un modelo descubierto por F1.
 * - [id] identificador canónico (ej: "@cf/meta/llama-3.2-3b-instruct")
 * - [provider] proveedor ("cloudflare-workers-ai", "puter", "pollinations", etc.)
 * - [capabilities] capacidades declaradas (chat, completion, embedding, etc.)
 * - [contextWindow] ventana de contexto en tokens
 * - [pricing] coste por 1M tokens (input/output) en USD, 0 = gratis
 * - [lastVerified] timestamp Unix ms de la última verificación exitosa (F3)
 * - [failureCount] fallos consecutivos de verificación (cuarentena a 3)
 * - [quarantined] true si está en cuarentena (≥3 fallos consecutivos)
 * - [metadata] extensible: deprecation, maxOutputTokens, etc.
 */
@Serializable
data class ModelEntry(
    val id: String,
    val provider: String,
    val capabilities: List<String>,
    val contextWindow: Int,
    val pricing: Pricing,
    val lastVerified: Long = 0L,
    val failureCount: Int = 0,
    val quarantined: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** True si la entrada está fresca según TTL (7 días por defecto). */
    fun isFresh(ttlMs: Long = DefaultModelCatalog.DEFAULT_TTL_MS): Boolean =
        lastVerified > 0 && (System.currentTimeMillis() - lastVerified) < ttlMs

    /** True si debe ser excluida del pool activo (cuarentena o TTL vencido sin verificación). */
    fun isStaleOrQuarantined(ttlMs: Long = DefaultModelCatalog.DEFAULT_TTL_MS): Boolean =
        quarantined || (lastVerified > 0 && !isFresh(ttlMs))

    /** Crea copia con verificación exitosa (reset failureCount, actualiza lastVerified). */
    fun verifiedNow(): ModelEntry = copy(
        lastVerified = System.currentTimeMillis(),
        failureCount = 0,
        quarantined = false,
    )

    /** Crea copia con fallo de verificación (incrementa failureCount, cuarentena a 3). */
    fun withFailure(): ModelEntry = copy(
        failureCount = failureCount + 1,
        quarantined = (failureCount + 1) >= DefaultModelCatalog.QUARANTINE_THRESHOLD,
    )

    override fun toString(): String = Json.Default.encodeToString(this)
}

/** Coste por 1M tokens (input / output) en USD. 0 = gratis / sin coste declarado. */
@Serializable
data class Pricing(
    val inputPer1M: Double = 0.0,
    val outputPer1M: Double = 0.0,
) {
    companion object {
        val FREE = Pricing()
    }
}

