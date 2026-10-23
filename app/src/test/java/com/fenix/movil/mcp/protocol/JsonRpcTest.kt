// ============================================================================
// JsonRpcTest.kt — verificación de C2 (gate: testDebugUnitTest --tests '*JsonRpc*')
// Cobertura: serialización/deserialización JSON-RPC 2.0, códigos de error, helpers
// ============================================================================

package com.fenix.movil.mcp.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class JsonRpcTest {

    private val json = Json.Default

    // ------------------------------------------------------------------------
    // JsonRpcRequest
    // ------------------------------------------------------------------------

    @Test
    fun jsonRpcRequestSerializesWithJsonrpc20ByDefault() {
        val request = JsonRpcRequest(method = "tools/list")
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JsonRpcRequest>(encoded)

        assertThat(decoded.jsonrpc).isEqualTo("2.0")
        assertThat(decoded.method).isEqualTo("tools/list")
        assertThat(decoded.id).isEqualTo(JsonNull)
        assertThat(decoded.params).isEqualTo(JsonNull)
    }

    @Test
    fun jsonRpcRequestSerializesCustomIdAndParams() {
        val request = JsonRpcRequest(
            id = JsonPrimitive("req-123"),
            method = "tools/call",
            params = JsonObject(mapOf(
                "name" to JsonPrimitive("test_tool"),
                "arguments" to JsonObject(emptyMap())
            ))
        )
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JsonRpcRequest>(encoded)

        assertThat(decoded.id).isEqualTo(JsonPrimitive("req-123"))
        assertThat(decoded.method).isEqualTo("tools/call")
        assertThat(decoded.params).isInstanceOf(JsonObject::class.java)
    }

    @Test
    fun jsonRpcRequestDeserializesFromValidJson() {
        val jsonStr = """{"jsonrpc":"2.0","id":42,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
        val decoded = json.decodeFromString<JsonRpcRequest>(jsonStr)

        assertThat(decoded.jsonrpc).isEqualTo("2.0")
        assertThat(decoded.id).isEqualTo(JsonPrimitive(42))
        assertThat(decoded.method).isEqualTo("initialize")
        assertThat(decoded.params).isInstanceOf(JsonObject::class.java)
    }

    // ------------------------------------------------------------------------
    // JsonRpcResponse.Success
    // ------------------------------------------------------------------------

    @Test
    fun jsonRpcResponseSuccessSerializesCorrectly() {
        val response = JsonRpcResponse.success("req-1", mapOf("tools" to listOf()))
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Success).isTrue()
        val success = decoded as JsonRpcResponse.Success
        assertThat(success.jsonrpc).isEqualTo("2.0")
        assertThat(success.id).isEqualTo(JsonPrimitive("req-1"))
        assertThat(success.result).isInstanceOf(JsonObject::class.java)
    }

    @Test
    fun jsonRpcResponseSuccessWithNumericId() {
        val response = JsonRpcResponse.success(42, "result value")
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Success).isTrue()
        val success = decoded as JsonRpcResponse.Success
        assertThat(success.id).isEqualTo(JsonPrimitive(42))
    }

    @Test
    fun jsonRpcResponseSuccessWithNullIdProducesJsonNull() {
        val response = JsonRpcResponse.success(null, mapOf("key" to "value"))
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        val success = decoded as JsonRpcResponse.Success
        assertThat(success.id).isEqualTo(JsonNull)
    }

    // ------------------------------------------------------------------------
    // JsonRpcResponse.Error
    // ------------------------------------------------------------------------

    @Test
    fun jsonRpcResponseErrorSerializesWithCodeAndMessage() {
        val error = McpError(McpErrorCode.METHOD_NOT_FOUND, "Method not found: tools/call")
        val response = JsonRpcResponse.error("req-1", error)
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Error).isTrue()
        val err = decoded as JsonRpcResponse.Error
        assertThat(err.jsonrpc).isEqualTo("2.0")
        assertThat(err.id).isEqualTo(JsonPrimitive("req-1"))
        assertThat(err.error.code).isEqualTo(McpErrorCode.METHOD_NOT_FOUND)
        assertThat(err.error.message).isEqualTo("Method not found: tools/call")
        assertThat(err.error.data).isEqualTo(JsonNull)
    }

    @Test
    fun jsonRpcResponseErrorSerializesAdditionalData() {
        val error = McpError(
            code = McpErrorCode.INVALID_PARAMS,
            message = "Invalid params",
            data = JsonObject(mapOf(
                "details" to JsonPrimitive("missing required field")
            ))
        )
        val response = JsonRpcResponse.error(123, error)
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        val err = decoded as JsonRpcResponse.Error
        assertThat(err.error.data).isInstanceOf(JsonObject::class.java)
        val dataObj = err.error.data as JsonObject
        assertThat(dataObj.get("details")).isEqualTo(JsonPrimitive("missing required field"))
    }

    // ------------------------------------------------------------------------
    // JsonRpcResponse.Notification
    // ------------------------------------------------------------------------

    @Test
    fun jsonRpcResponseNotificationSerializesWithoutId() {
        val response = JsonRpcResponse.notification("notifications/tools/list_changed")
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Notification).isTrue()
        val notif = decoded as JsonRpcResponse.Notification
        assertThat(notif.jsonrpc).isEqualTo("2.0")
        assertThat(notif.method).isEqualTo("notifications/tools/list_changed")
        assertThat(notif.params).isEqualTo(JsonNull)
    }

    @Test
    fun jsonRpcResponseNotificationWithParams() {
        val response = JsonRpcResponse.notification(
            "notifications/resources/updated",
            mapOf("uri" to "file:///test.txt")
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        val notif = decoded as JsonRpcResponse.Notification
        assertThat(notif.params).isInstanceOf(JsonObject::class.java)
    }

    // ------------------------------------------------------------------------
    // McpErrorCode
    // ------------------------------------------------------------------------

    @Test
    fun mcpErrorCodeHasStandardJsonRpc20Codes() {
        assertThat(McpErrorCode.PARSE_ERROR).isEqualTo(-32700)
        assertThat(McpErrorCode.INVALID_REQUEST).isEqualTo(-32600)
        assertThat(McpErrorCode.METHOD_NOT_FOUND).isEqualTo(-32601)
        assertThat(McpErrorCode.INVALID_PARAMS).isEqualTo(-32602)
        assertThat(McpErrorCode.INTERNAL_ERROR).isEqualTo(-32603)
    }

    @Test
    fun mcpErrorCodeHasMcpSpecificCodesInRange() {
        assertThat(McpErrorCode.RESOURCE_NOT_FOUND).isEqualTo(-32000)
        assertThat(McpErrorCode.PROMPT_NOT_FOUND).isEqualTo(-32001)
        assertThat(McpErrorCode.TOOL_NOT_FOUND).isEqualTo(-32002)
        assertThat(McpErrorCode.INVALID_TOOL_PARAMS).isEqualTo(-32003)

        // Verify range
        assertThat(McpErrorCode.RESOURCE_NOT_FOUND).isAtLeast(-32099)
        assertThat(McpErrorCode.RESOURCE_NOT_FOUND).isAtMost(-32000)
    }

    // ------------------------------------------------------------------------
    // McpError
    // ------------------------------------------------------------------------

    @Test
    fun mcpErrorSerializesWithOptionalData() {
        val error = McpError(
            code = McpErrorCode.INTERNAL_ERROR,
            message = "Server error"
        )
        val encoded = json.encodeToString(error)
        val decoded = json.decodeFromString<McpError>(encoded)

        assertThat(decoded.code).isEqualTo(McpErrorCode.INTERNAL_ERROR)
        assertThat(decoded.message).isEqualTo("Server error")
        assertThat(decoded.data).isEqualTo(JsonNull)
    }

    @Test
    fun mcpErrorSerializesDataWhenPresent() {
        val error = McpError(
            code = McpErrorCode.INVALID_TOOL_PARAMS,
            message = "Invalid tool parameters",
            data = JsonObject(mapOf(
                "param" to JsonPrimitive("timeout"),
                "reason" to JsonPrimitive("must be positive")
            ))
        )
        val encoded = json.encodeToString(error)
        val decoded = json.decodeFromString<McpError>(encoded)

        assertThat(decoded.data).isInstanceOf(JsonObject::class.java)
    }

    // ------------------------------------------------------------------------
    // Round-trip encoding/decoding
    // ------------------------------------------------------------------------

    @Test
    fun roundTripRequestJsonRequestPreservesAllFields() {
        val original = JsonRpcRequest(
            id = JsonPrimitive("round-trip-1"),
            method = "initialize",
            params = JsonObject(mapOf(
                "protocolVersion" to JsonPrimitive("2025-06-18"),
                "capabilities" to JsonObject(emptyMap())
            ))
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<JsonRpcRequest>(encoded)

        assertThat(decoded.jsonrpc).isEqualTo(original.jsonrpc)
        assertThat(decoded.id).isEqualTo(original.id)
        assertThat(decoded.method).isEqualTo(original.method)
        assertThat(decoded.params).isEqualTo(original.params)
    }

    @Test
    fun roundTripResponseSuccessJsonResponseSuccess() {
        val original = JsonRpcResponse.success("id-1", mapOf("status" to "ok"))
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Success).isTrue()
        val success = decoded as JsonRpcResponse.Success
        assertThat(success.id).isEqualTo(original.id)
        assertThat(success.result).isEqualTo(original.result)
    }

    @Test
    fun roundTripResponseErrorJsonResponseError() {
        val error = McpError(McpErrorCode.INVALID_PARAMS, "bad params")
        val original = JsonRpcResponse.error(999, error)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Error).isTrue()
        val err = decoded as JsonRpcResponse.Error
        assertThat(err.id).isEqualTo(original.id)
        assertThat(err.error.code).isEqualTo(original.error.code)
        assertThat(err.error.message).isEqualTo(original.error.message)
    }

    @Test
    fun roundTripNotificationJsonNotification() {
        val original = JsonRpcResponse.notification("test/event", mapOf("data" to "value"))
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)

        assertThat(decoded is JsonRpcResponse.Notification).isTrue()
        val notif = decoded as JsonRpcResponse.Notification
        assertThat(notif.method).isEqualTo(original.method)
        assertThat(notif.params).isEqualTo(original.params)
    }

    // ------------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------------

    @Test
    fun jsonRpcRequestWithNullParamsUsesJsonNull() {
        val request = JsonRpcRequest(method = "ping")
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JsonRpcRequest>(encoded)

        assertThat(decoded.params).isEqualTo(JsonNull)
    }

    @Test
    fun responseHelperToJsonElementHandlesBasicTypes() {
        // Test via success helper which uses toJsonElement internally
        val responseStr = JsonRpcResponse.success("id", "string value")
        val responseNum = JsonRpcResponse.success("id", 42)
        val responseBool = JsonRpcResponse.success("id", true)
        val responseNull = JsonRpcResponse.success("id", null)
        val responseMap = JsonRpcResponse.success("id", mapOf("key" to "value"))
        val responseList = JsonRpcResponse.success("id", listOf(1, 2, 3))

        // All should encode without throwing
        assertThat(json.encodeToString(responseStr)).contains("string value")
        assertThat(json.encodeToString(responseNum)).contains("42")
        assertThat(json.encodeToString(responseBool)).contains("true")
        assertThat(json.encodeToString(responseNull)).contains("null")
        assertThat(json.encodeToString(responseMap)).contains("key")
        assertThat(json.encodeToString(responseList)).contains("1")
    }
}// TEST MARKER
