// ============================================================================
// Initialize.kt — handshake MCP initialize / notifications/initialized
// ============================================================================

package com.fenix.movil.mcp.protocol

import kotlinx.serialization.Serializable

/** Request params para initialize. */
@Serializable
data class InitializeRequest(
    val protocolVersion: String,
    val capabilities: ClientCapabilities? = null,
    val clientInfo: ClientInfo? = null,
)

/** Capacidades del cliente. */
@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: SamplingCapability? = null,
    val elicitation: ElicitationCapability? = null,
) {
    /** Convierte a Map para almacenamiento en SessionState. */
    fun toMap(): Map<String, Any> = buildMap<String, Any> {
        roots?.let { put("roots", mapOf("listChanged" to it.listChanged)) }
        if (sampling != null) put("sampling", emptyMap<String, Any>())
        if (elicitation != null) put("elicitation", emptyMap<String, Any>())
    }
}

@Serializable
data class RootsCapability(val listChanged: Boolean = false)

/** Capacidades vacías - usan object para serialización. */
@Serializable
object SamplingCapability

@Serializable
object ElicitationCapability

/** Info del cliente. */
@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
)

/** Response de initialize. */
@Serializable
data class InitializeResponse(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
    val instructions: String? = null,
) {
    @Serializable
    data class ServerInfo(
        val name: String,
        val version: String,
    )

    @Serializable
    data class ServerCapabilities(
        val tools: ToolsCapability? = null,
        val resources: ResourcesCapability? = null,
        val prompts: PromptsCapability? = null,
        val logging: LoggingCapability? = null,
    ) {
        @Serializable
        data class ToolsCapability(val listChanged: Boolean = false)

        @Serializable
        data class ResourcesCapability(
            val subscribe: Boolean = false,
            val listChanged: Boolean = false,
        )

        @Serializable
        data class PromptsCapability(val listChanged: Boolean = false)

        @Serializable
        object LoggingCapability
    }
}

/** Capacidades del servidor (alias para uso interno). */
typealias ServerCapabilities = InitializeResponse.ServerCapabilities

