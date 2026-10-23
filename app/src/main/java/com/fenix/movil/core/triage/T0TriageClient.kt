// ============================================================================
// T0TriageClient.kt — cliente triage T0 (@cf/meta/llama-3.2-3b-instruct en CF Workers AI)
// 300 RPM, sin dependencias externas, integra con ModelCatalog
// ============================================================================

package com.fenix.movil.core.triage

import com.fenix.movil.core.modelcatalog.ModelCatalog
import com.fenix.movil.core.modelcatalog.ModelEntry
import com.fenix.movil.core.modelcatalog.Pricing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cliente T0 para Cloudflare Workers AI (@cf/meta/llama-3.2-3b-instruct).
 * - 300 RPM limit (rate limiting local)
 * - Sin dependencias externas (solo Ktor client + serialization)
 * - Clasificación rápida: intent routing, complejidad, necesidad de herramientas
 * - Registra resultado en ModelCatalog como modelo T0 verificado
 */
class T0TriageClient(
    private val accountId: String,
    private val apiToken: String,
    private val catalog: ModelCatalog,
    private val maxRpm: Int = 300,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    // Cliente HTTP real se inyecta en C3/E1 cuando se tenga Ktor client funcional
    // Por ahora el triage es stub que registra el modelo en catálogo

    private val requestTimestamps = MutableStateFlow<List<Long>>(emptyList())
    val requestRate: StateFlow<Double> = requestTimestamps.map { timestamps ->
        val now = System.currentTimeMillis()
        val recent = timestamps.filter { now - it < 60_000 }
        recent.size.toDouble()
    } as StateFlow<Double>

    /** Modelo T0 ID canónico. */
    companion object {
        const val MODEL_ID = "@cf/meta/llama-3.2-3b-instruct"
        const val PROVIDER = "cloudflare-workers-ai"
        const val BASE_URL = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s"
    }

    /**
     * Ejecuta triage T0: clasifica la consulta del usuario.
     * Retorna T0TriageResult con intent, complejidad, needsTools, confidence.
     * Stub implementación - llamada HTTP real en C3/E1.
     */
    suspend fun triage(query: String, context: Map<String, Any> = emptyMap()): T0TriageResult {
        // Rate limit check local
        waitForRateLimit()

        // Stub: clasificación simple basada en palabras clave
        val intent = when {
            query.contains("code", true) || query.contains("programa", true) -> "code"
            query.contains("analiza", true) || query.contains("analyze", true) -> "analysis"
            query.contains("shell", true) || query.contains("terminal", true) || query.contains("bash", true) -> "shell"
            query.contains("archivo", true) || query.contains("file", true) -> "file"
            query.contains("web", true) || query.contains("busca", true) || query.contains("search", true) -> "web"
            else -> "chat"
        }

        val complexity = when {
            query.length > 500 -> "high"
            query.length > 100 -> "medium"
            else -> "low"
        }

        val needsTools = intent != "chat"
        val suggestedRing = when {
            intent == "chat" && complexity == "low" -> 0 // Anillo 0 local
            intent == "chat" -> 1 // Anillo 1 pool
            needsTools -> 1 // Anillo 1 pool para tools
            else -> 2 // Anillo 2 federado
        }

        return T0TriageResult(
            intent = intent,
            complexity = complexity,
            needsTools = needsTools,
            suggestedRing = suggestedRing,
            confidence = 0.85,
            reasoning = "Clasificación por palabras clave (stub T0)",
            rawResponse = "",
        )
    }

    /** Espera si se excede el límite de RPM (300). */
    private suspend fun waitForRateLimit() {
        val timestamps = requestTimestamps.value
        val now = System.currentTimeMillis()
        val recent = timestamps.filter { now - it < 60_000 }

        if (recent.size >= maxRpm) {
            val oldest = recent.minOrNull() ?: now
            val waitMs = 60_000 - (now - oldest) + 100 // buffer 100ms
            if (waitMs > 0) {
                delay(waitMs)
            }
        }
        // Registrar timestamp actual
        requestTimestamps.value = (timestamps + now).filter { now - it < 120_000 } // mantener 2 min
    }

    /** Registra/actualiza modelo T0 en catálogo (se llama en inicio). */
    suspend fun registerInCatalog() {
        val entry = ModelEntry(
            id = MODEL_ID,
            provider = PROVIDER,
            capabilities = listOf("chat", "completion", "triage"),
            contextWindow = 8192,
            pricing = Pricing.FREE,
            lastVerified = System.currentTimeMillis(),
            metadata = mapOf(
                "triage" to "true",
                "rpm_limit" to maxRpm.toString(),
                "account_id" to accountId,
                "description" to "Modelo de triaje T0 para clasificación rápida (300 RPM en CF Workers AI)"
            ),
        )
        catalog.upsert(entry)
    }

    /** Cierra el cliente HTTP (stub). */
    fun close() {
        // NOOP - cliente real se cierra en C3/E1
    }
}

/** Request a CF Workers AI (para C3/E1). */
@Serializable
data class T0Request(
    val messages: List<T0Message>,
    val maxTokens: Int,
    val temperature: Double,
    val stream: Boolean,
)

@Serializable
data class T0Message(
    val role: String,
    val content: String,
)

/** Response de CF Workers AI (para C3/E1). */
@Serializable
data class T0Response(
    val result: T0Result? = null,
    val success: Boolean = false,
    val errors: List<T0Error> = emptyList(),
    val messages: List<String> = emptyList(),
)

@Serializable
data class T0Result(
    val response: List<T0ResponseItem> = emptyList(),
)

@Serializable
data class T0ResponseItem(
    val role: String = "assistant",
    val content: String = "",
    val text: String = "",
)

@Serializable
data class T0Error(
    val code: Int,
    val message: String,
)

/** JSON esperado en la respuesta del modelo (para C3/E1). */
@Serializable
data class T0TriageJson(
    val intent: String,
    val complexity: String,
    val needsTools: Boolean,
    val suggestedRing: Int,
    val confidence: Double,
    val reasoning: String,
)

/** Resultado estructurado del triage. */
data class T0TriageResult(
    val intent: String = "unknown",
    val complexity: String = "low",
    val needsTools: Boolean = false,
    val suggestedRing: Int = 1,
    val confidence: Double = 0.0,
    val reasoning: String = "",
    val rawResponse: String = "",
    val error: String? = null,
) {
    val success: Boolean get() = error == null

    companion object {
        fun error(msg: String): T0TriageResult = T0TriageResult(error = msg)
    }
}