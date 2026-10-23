// ============================================================================
// McpServer.kt — servidor Ktor embebido localhost:8080/mcp + /v1/models
// Implementación mínima compatible con Ktor 3.x
// ============================================================================

package com.fenix.movil.mcp.server

import com.fenix.movil.core.modelcatalog.DefaultModelCatalog
import com.fenix.movil.core.modelcatalog.ModelCatalog
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Servidor MCP embebido en Ktor (CIO engine, loopback-only).
 * Expone:
 * - POST /mcp — Streamable HTTP JSON-RPC 2.0 (MCP)
 * - GET  /mcp — SSE streaming para notificaciones server→client
 * - DELETE /mcp — terminación de sesión
 * - GET  /v1/models — descubrimiento F1 (pipeline 5 filtros)
 * - GET  /health — health check
 */
class EmbeddedMcpServer(
    private val port: Int = 8080,
    private val host: String = "127.0.0.1",
    private val catalog: ModelCatalog? = null,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val internalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalogInstance = catalog ?: DefaultModelCatalog(
        persistenceDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "fenix-mcp"),
        backgroundScope = internalScope,
    )
    private val mcpServer = McpServerImpl(catalogInstance)
    private var serverJob: Job? = null
    private var started = false

    /** Inicia el servidor (no bloqueante). */
    fun start(): Boolean {
        if (started) return true
        serverJob = internalScope.launch {
            embeddedServer(CIO, host = host, port = port) {
                install(ContentNegotiation)

                routing {
                    get("/health") {
                        call.respondText("OK")
                    }
                    mcpRoute(mcpServer)
                    modelsRoute(catalogInstance)
                }
            }.start(wait = true)
        }
        started = true
        return true
    }

    /** Detiene el servidor. */
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        started = false
        internalScope.coroutineContext[Job]?.cancel()
    }

    /** Verifica si el servidor está corriendo. */
    fun isRunning(): Boolean = started

    /** Obtiene el catálogo de modelos. */
    fun getCatalog(): ModelCatalog = catalogInstance

    /** Obtiene el servidor MCP interno. */
    fun getMcpServer(): McpServerImpl = mcpServer

    /** Puerto en el que escucha. */
    val listeningPort: Int = port

    /** Host en el que escucha. */
    val listeningHost: String = host

    /** URL base del servidor. */
    val baseUrl: String = "http://$host:$port"
}