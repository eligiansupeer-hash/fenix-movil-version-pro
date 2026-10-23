// ============================================================================
// McpRoute.kt — endpoint /mcp (Streamable HTTP JSON-RPC 2.0)
// ============================================================================

package com.fenix.movil.mcp.server

import com.fenix.movil.mcp.protocol.InitializeRequest
import com.fenix.movil.mcp.protocol.InitializeResponse
import com.fenix.movil.mcp.protocol.JsonRpcRequest
import com.fenix.movil.mcp.protocol.JsonRpcResponse
import com.fenix.movil.mcp.protocol.McpError
import com.fenix.movil.mcp.protocol.McpErrorCode
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Genera ID de sesión único.
 */
private fun generateSessionId(counter: AtomicLong): String = "mcp-${System.currentTimeMillis()}-${counter.incrementAndGet()}"

/**
 * Ruta POST /mcp — Streamable HTTP para MCP (JSON-RPC 2.0).
 * Implementa:
 * - initialize / notifications/initialized (handshake)
 * - tools/list, tools/call (C3)
 * - resources/list, resources/read (futuro)
 * - prompts/list, prompts/get (futuro)
 * - Gestión de mcp-session-id (header + body)
 */
fun Routing.mcpRoute(server: McpServerImpl) {
    route("/mcp") {
        // POST principal: maneja todos los métodos JSON-RPC 2.0
        post {
            val sessionId = call.request.headers["mcp-session-id"]
                ?: call.request.headers["Mcp-Session-Id"]
                ?: generateSessionId(server.requestCounter)

            val body = call.receiveText()
            val request = try {
                Json.Default.decodeFromString<JsonRpcRequest>(body)
            } catch (e: Exception) {
                val error = McpError(McpErrorCode.PARSE_ERROR, "Invalid JSON: ${e.message}")
                call.respond(Json.Default.encodeToString(JsonRpcResponse.error(null, error)))
                return@post
            }

            val response = server.handleRequest(request, sessionId)
            call.response.headers.append("mcp-session-id", sessionId)
            call.respond(Json.Default.encodeToString(response))
        }

        // GET para SSE streaming (opcional, para notificaciones server→client)
        get {
            val sessionId = call.request.headers["mcp-session-id"]
                ?: call.request.headers["Mcp-Session-Id"]
                ?: generateSessionId(server.requestCounter)

            // Streaming SSE - mantiene conexión abierta para notificaciones
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Enviar evento de conexión
                write("event: connected\ndata: {\"sessionId\":\"$sessionId\"}\n\n")
                flush()

                val channel = Channel<String>(Channel.UNLIMITED)
                server.registerSseSession(sessionId, channel)

                // Mantener conexión y enviar eventos del canal
                try {
                    for (event in channel) {
                        write(event)
                        flush()
                    }
                } catch (e: Exception) {
                    // Cliente desconectado
                } finally {
                    server.unregisterSseSession(sessionId)
                    channel.close()
                }
            }
        }

        // DELETE para terminar sesión explícitamente
        delete {
            val sessionId = call.request.headers["mcp-session-id"]
                ?: call.request.headers["Mcp-Session-Id"]
            if (sessionId != null) {
                server.terminateSession(sessionId)
            }
            call.respondText("OK")
        }
    }
}

/** Servidor MCP con estado de sesiones y capacidades. */
class McpServerImpl(
    private val catalog: com.fenix.movil.core.modelcatalog.ModelCatalog,
) {
    private val _sessions = ConcurrentHashMap<String, SessionState>()
    private val _sseSessions = ConcurrentHashMap<String, Channel<String>>()
    val requestCounter = AtomicLong(0)
    private val _initialized = MutableStateFlow(false)
    val initialized: kotlinx.coroutines.flow.StateFlow<Boolean> = _initialized.asStateFlow()

    /** Acceso de solo lectura a sesiones para tests. */
    val sessions: ConcurrentHashMap<String, SessionState> = _sessions

    /** Acceso de solo lectura a sesiones SSE para tests. */
    val sseSessions: ConcurrentHashMap<String, Channel<String>> = _sseSessions

    data class SessionState(
        val id: String,
        var initialized: Boolean = false,
        var clientCapabilities: Map<String, Any> = emptyMap(),
    )

    /** Maneja una request JSON-RPC 2.0 y devuelve response. */
    fun handleRequest(request: JsonRpcRequest, sessionId: String): JsonRpcResponse {
        val session = sessions.computeIfAbsent(sessionId) { SessionState(it) }

        return when (request.method) {
            "initialize" -> handleInitialize(request, session)
            "notifications/initialized" -> handleInitializedNotification(request, session)
            "tools/list" -> handleToolsList(request, session)
            "tools/call" -> handleToolsCall(request, session)
            "resources/list" -> handleResourcesList(request, session)
            "resources/read" -> handleResourcesRead(request, session)
            "prompts/list" -> handlePromptsList(request, session)
            "prompts/get" -> handlePromptsGet(request, session)
            else -> JsonRpcResponse.error(
                request.id,
                McpError(McpErrorCode.METHOD_NOT_FOUND, "Method not found: ${request.method}")
            )
        }
    }

    private fun handleInitialize(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        val initRequest = try {
            Json.Default.decodeFromString<InitializeRequest>(Json.Default.encodeToString(request.params))
        } catch (e: Exception) {
            return JsonRpcResponse.error(
                request.id,
                McpError(McpErrorCode.INVALID_PARAMS, "Invalid initialize params: ${e.message}")
            )
        }

        session.initialized = true
        session.clientCapabilities = initRequest.capabilities?.toMap() ?: emptyMap()
        _initialized.value = true

        val response = InitializeResponse(
            protocolVersion = "2025-06-18",
            capabilities = InitializeResponse.ServerCapabilities(
                tools = InitializeResponse.ServerCapabilities.ToolsCapability(listChanged = true),
                resources = InitializeResponse.ServerCapabilities.ResourcesCapability(subscribe = true, listChanged = true),
                prompts = InitializeResponse.ServerCapabilities.PromptsCapability(listChanged = true),
            ),
            serverInfo = InitializeResponse.ServerInfo(
                name = "fenix-movil-mcp",
                version = "1.0.0",
            ),
        )
        return JsonRpcResponse.success(request.id, response)
    }

    private fun handleInitializedNotification(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        session.initialized = true
        _initialized.value = true
        return JsonRpcResponse.success(null, Unit)
    }

    private fun handleToolsList(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        val tools = listOf<Map<String, Any>>()
        return JsonRpcResponse.success(request.id, mapOf("tools" to tools as List<Any>))
    }

    private fun handleToolsCall(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        return JsonRpcResponse.error(
            request.id,
            McpError(McpErrorCode.INTERNAL_ERROR, "Tools not implemented yet (C3)")
        )
    }

    private fun handleResourcesList(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        return JsonRpcResponse.success(request.id, mapOf("resources" to emptyList<Any>()))
    }

    private fun handleResourcesRead(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        return JsonRpcResponse.error(
            request.id,
            McpError(McpErrorCode.INTERNAL_ERROR, "Resources not implemented yet")
        )
    }

    private fun handlePromptsList(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        return JsonRpcResponse.success(request.id, mapOf("prompts" to emptyList<Any>()))
    }

    private fun handlePromptsGet(request: JsonRpcRequest, session: SessionState): JsonRpcResponse {
        return JsonRpcResponse.error(
            request.id,
            McpError(McpErrorCode.INTERNAL_ERROR, "Prompts not implemented yet")
        )
    }

    fun registerSseSession(sessionId: String, channel: Channel<String>) {
        sseSessions[sessionId] = channel
    }

    fun unregisterSseSession(sessionId: String) {
        sseSessions.remove(sessionId)?.close()
    }

    fun terminateSession(sessionId: String) {
        sessions.remove(sessionId)
        unregisterSseSession(sessionId)
    }

    /** Envía notificación a todas las sesiones SSE activas. */
    fun broadcastNotification(method: String, params: Any) {
        val notification = JsonRpcResponse.notification(method, params)
        val json = Json.Default.encodeToString(notification)
        val event = "event: $method\ndata: $json\n\n"
        sseSessions.values.forEach { it.trySend(event) }
    }
}