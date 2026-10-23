// ============================================================================
// JsonRpc.kt — tipos base JSON-RPC 2.0 para MCP
// ============================================================================

package com.fenix.movil.mcp.protocol

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request JSON-RPC 2.0. */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    @Contextual val id: JsonElement = JsonNull,
    val method: String,
    @Contextual val params: JsonElement = JsonNull,
)

/** Response JSON-RPC 2.0 (success o error). */
@Serializable
sealed class JsonRpcResponse {
    @Serializable
    data class Success(
        val jsonrpc: String = "2.0",
        @Contextual val id: JsonElement = JsonNull,
        @Contextual val result: JsonElement,
    ) : JsonRpcResponse()

    @Serializable
    data class Error(
        val jsonrpc: String = "2.0",
        @Contextual val id: JsonElement = JsonNull,
        val error: McpError,
    ) : JsonRpcResponse()

    @Serializable
    data class Notification(
        val jsonrpc: String = "2.0",
        val method: String,
        @Contextual val params: JsonElement = JsonNull,
    ) : JsonRpcResponse()

    companion object {
        fun success(id: Any?, result: Any): JsonRpcResponse = Success(
            id = toJsonElement(id),
            result = toJsonElement(result),
        )
        fun error(id: Any?, error: McpError): JsonRpcResponse = Error(
            id = toJsonElement(id),
            error = error,
        )
        fun notification(method: String, params: Any? = null): JsonRpcResponse = Notification(
            method = method,
            params = toJsonElement(params),
        )

        @Suppress("UNUSED_PARAMETER")
        private fun toJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            is Map<*, *> -> {
                val map = mutableMapOf<String, JsonElement>()
                value.forEach { (k, v) -> map[k.toString()] = toJsonElement(v) }
                JsonObject(map)
            }
            is List<*> -> {
                val map = mutableMapOf<String, JsonElement>()
                value.forEachIndexed { index, v -> map[index.toString()] = toJsonElement(v) }
                JsonObject(map)
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}

/** Error MCP estándar. */
@Serializable
data class McpError(
    val code: Int,
    val message: String,
    @Contextual val data: JsonElement = JsonNull,
)

/** Códigos de error MCP (subconjunto JSON-RPC 2.0 + MCP específico). */
object McpErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    // MCP específicos (-32000 a -32099)
    const val RESOURCE_NOT_FOUND = -32000
    const val PROMPT_NOT_FOUND = -32001
    const val TOOL_NOT_FOUND = -32002
    const val INVALID_TOOL_PARAMS = -32003
}