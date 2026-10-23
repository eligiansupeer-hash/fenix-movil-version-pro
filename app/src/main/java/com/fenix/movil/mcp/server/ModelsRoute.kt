// ============================================================================
// ModelsRoute.kt — endpoint /v1/models (descubrimiento F1 del pipeline 5 filtros)
// ============================================================================

package com.fenix.movil.mcp.server

import com.fenix.movil.core.modelcatalog.ModelCatalog
import com.fenix.movil.core.modelcatalog.ModelEntry
import com.fenix.movil.core.modelcatalog.Pricing as ModelPricing
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * Ruta GET /v1/models — descubrimiento F1 del pipeline 5 filtros.
 * Respuesta compatible con OpenAI /v1/models:
 * {
 *   "object": "list",
 *   "data": [ { "id": "...", "object": "model", "owned_by": "...", ... }, ... ]
 * }
 */
fun Routing.modelsRoute(catalog: ModelCatalog) {
    get("/v1/models") {
        val models = catalog.available.value
        val data = models.map { entry ->
            ModelsResponse.Model(
                id = entry.id,
                objectField = "model",
                created = entry.lastVerified / 1000, // Unix timestamp en segundos
                ownedBy = entry.provider,
                capabilities = entry.capabilities,
                contextWindow = entry.contextWindow,
                pricing = ModelsResponse.ModelPricing(
                    inputPer1M = entry.pricing.inputPer1M,
                    outputPer1M = entry.pricing.outputPer1M,
                ),
                metadata = entry.metadata,
            )
        }
        val response = ModelsResponse(objectField = "list", data = data)
        call.respond(Json.Default.encodeToString(response))
    }
}

/** Respuesta serializable para /v1/models. */
@kotlinx.serialization.Serializable
data class ModelsResponse(
    @kotlinx.serialization.SerialName("object") val objectField: String,
    val data: List<ModelsResponse.Model>,
) {
    @kotlinx.serialization.Serializable
    data class Model(
        val id: String,
        @kotlinx.serialization.SerialName("object") val objectField: String,
        val created: Long,
        @kotlinx.serialization.SerialName("owned_by") val ownedBy: String,
        val capabilities: List<String>,
        val contextWindow: Int,
        val pricing: ModelPricing,
        val metadata: Map<String, String>,
    )

    @kotlinx.serialization.Serializable
    data class ModelPricing(
        val inputPer1M: Double,
        val outputPer1M: Double,
    )
}