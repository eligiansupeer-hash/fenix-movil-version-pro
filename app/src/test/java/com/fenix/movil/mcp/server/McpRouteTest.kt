// ============================================================================
// McpRouteTest.kt — verificación de C2 (gate: testDebugUnitTest --tests '*McpRoute*')
// Cobertura: handlers initialize, notifications/initialized, tools/list, tools/call,
//            resources/*, prompts/*, mcp-session-id, SSE, error codes
// ============================================================================

package com.fenix.movil.mcp.server

import com.fenix.movil.core.modelcatalog.DefaultModelCatalog
import com.fenix.movil.core.modelcatalog.ModelCatalog
import com.fenix.movil.core.modelcatalog.ModelEntry
import com.fenix.movil.core.modelcatalog.Pricing
import com.fenix.movil.mcp.protocol.InitializeRequest
import com.fenix.movil.mcp.protocol.JsonRpcRequest
import com.fenix.movil.mcp.protocol.JsonRpcResponse
import com.fenix.movil.mcp.protocol.McpError
import com.fenix.movil.mcp.protocol.McpErrorCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Helper para llamar funciones suspendidas en tests
private fun <T> runTest(block: suspend () -> T): T = runBlocking { block() }

class McpRouteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var persistenceDir: File
    private lateinit var catalog: ModelCatalog
    private lateinit var server: McpServerImpl

    @Before
    fun setUp() {
        persistenceDir = tmp.newFolder("modelcatalog")
        catalog = DefaultModelCatalog(
            persistenceDir = persistenceDir,
            ttlMs = 10_000,
            quarantineThreshold = 3,
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        )
        runTest { delay(10) }
        server = McpServerImpl(catalog)
    }

    @After
    fun tearDown() {
        // Cleanup handled by TemporaryFolder
    }

    // ------------------------------------------------------------------------
    // Helper functions
    // ------------------------------------------------------------------------

    private fun makeInitializeRequest(id: Any = "init-1"): JsonRpcRequest {
        val initRequest = InitializeRequest(
            protocolVersion = "2025-06-18",
            capabilities = com.fenix.movil.mcp.protocol.ClientCapabilities(),
            clientInfo = com.fenix.movil.mcp.protocol.ClientInfo(name = "test-client", version = "1.0.0")
        )
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "initialize",
            params = Json.encodeToJsonElement(initRequest)
        )
    }

    private fun makeInitializedNotification(): JsonRpcRequest {
        return JsonRpcRequest(
            method = "notifications/initialized",
            params = kotlinx.serialization.json.JsonNull
        )
    }

    private fun makeToolsListRequest(id: Any = "tools-list-1"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "tools/list",
            params = kotlinx.serialization.json.JsonNull
        )
    }

    private fun makeToolsCallRequest(id: Any = "tools-call-1", toolName: String = "test_tool"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "tools/call",
            params = Json.encodeToJsonElement(mapOf(
                "name" to toolName,
                "arguments" to mapOf<String, Any>()
            ))
        )
    }

    private fun makeResourcesListRequest(id: Any = "res-list-1"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "resources/list",
            params = kotlinx.serialization.json.JsonNull
        )
    }

    private fun makeResourcesReadRequest(id: Any = "res-read-1"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "resources/read",
            params = Json.encodeToJsonElement(mapOf("uri" to "file:///test.txt"))
        )
    }

    private fun makePromptsListRequest(id: Any = "prompts-list-1"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "prompts/list",
            params = kotlinx.serialization.json.JsonNull
        )
    }

    private fun makePromptsGetRequest(id: Any = "prompts-get-1"): JsonRpcRequest {
        return JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive(id.toString()),
            method = "prompts/get",
            params = Json.encodeToJsonElement(mapOf("name" to "test_prompt"))
        )
    }

    // ------------------------------------------------------------------------
    // initialize handler
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestInitializeReturnsInitializeResponseWithCapabilities() {
        val request = makeInitializeRequest()
        val response = server.handleRequest(request, "test-session-1")

        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success
        assertThat(success.id).isEqualTo(kotlinx.serialization.json.JsonPrimitive("init-1"))

        // Decode result as InitializeResponse
        val initResponse = Json.Default.decodeFromString<com.fenix.movil.mcp.protocol.InitializeResponse>(
            Json.Default.encodeToString(success.result)
        )

        assertThat(initResponse.protocolVersion).isEqualTo("2025-06-18")
        assertThat(initResponse.serverInfo.name).isEqualTo("fenix-movil-mcp")
        assertThat(initResponse.serverInfo.version).isEqualTo("1.0.0")
        assertThat(initResponse.capabilities.tools?.listChanged).isTrue()
        assertThat(initResponse.capabilities.resources?.subscribe).isTrue()
        assertThat(initResponse.capabilities.resources?.listChanged).isTrue()
        assertThat(initResponse.capabilities.prompts?.listChanged).isTrue()
    }

    @Test
    fun handleRequestInitializeMarksSessionAsInitialized() {
        val request = makeInitializeRequest()
        val sessionId = "session-init-test"

        val response = server.handleRequest(request, sessionId)

        assertThat(response is JsonRpcResponse.Success).isTrue()

        // Verify session is marked as initialized
        val session = server.sessions[sessionId]
        assertThat(session).isNotNull()
        assertThat(session!!.initialized).isTrue()
    }

    @Test
    fun handleRequestInitializeWithInvalidParamsReturnsErrorInvalidParams() {
        val request = JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive("bad-init"),
            method = "initialize",
            params = Json.encodeToJsonElement(mapOf("invalid" to "params"))
        )
        val response = server.handleRequest(request, "session-bad-init")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(McpErrorCode.INVALID_PARAMS)
        assertThat(error.error.message).contains("Invalid initialize params")
    }

    // ------------------------------------------------------------------------
    // notifications/initialized handler
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestNotificationsInitializedMarksSessionAsInitialized() {
        val sessionId = "session-initialized-test"
        val request = makeInitializedNotification()

        val response = server.handleRequest(request, sessionId)

        // Notifications don't have response (id = null)
        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success
        assertThat(success.id).isEqualTo(kotlinx.serialization.json.JsonNull)

        val session = server.sessions[sessionId]
        assertThat(session).isNotNull()
        assertThat(session!!.initialized).isTrue()
    }

    @Test
    fun handleRequestNotificationsInitializedUpdatesInitializedFlow() = runTest {
        // Initially false
        assertThat(server.initialized.first()).isFalse()

        val request = makeInitializedNotification()
        server.handleRequest(request, "session-flow-test")

        // After initialized, should be true
        assertThat(server.initialized.first()).isTrue()
    }

    // ------------------------------------------------------------------------
    // tools/list handler
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestToolsListReturnsEmptyListC3NotImplemented() {
        // First initialize the session
        server.handleRequest(makeInitializeRequest(), "session-tools-list")

        val request = makeToolsListRequest()
        val response = server.handleRequest(request, "session-tools-list")

        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success

        val result = Json.Default.decodeFromString<Map<String, Any>>(
            Json.Default.encodeToString(success.result)
        )
        assertThat(result["tools"]).isInstanceOf(List::class.java)
        assertThat((result["tools"] as List<*>).isEmpty()).isTrue()
    }

    @Test
    fun handleRequestToolsListWithoutInitializeReturnsEmptyList() {
        val request = makeToolsListRequest()
        val response = server.handleRequest(request, "session-no-init")

        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success

        val result = Json.Default.decodeFromString<Map<String, Any>>(
            Json.Default.encodeToString(success.result)
        )
        assertThat((result["tools"] as List<*>).isEmpty()).isTrue()
    }

    // ------------------------------------------------------------------------
    // tools/call handler
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestToolsCallReturnsErrorMethodNotFoundWithC3Message() {
        server.handleRequest(makeInitializeRequest(), "session-tools-call")

        val request = makeToolsCallRequest(toolName = "any_tool")
        val response = server.handleRequest(request, "session-tools-call")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(McpErrorCode.METHOD_NOT_FOUND)
        assertThat(error.error.message).contains("Method not found")
    }

    @Test
    fun handleRequestToolsCallReturnsError32601WithClearMessageAboutC3() {
        server.handleRequest(makeInitializeRequest(), "session-tools-call-msg")

        val request = makeToolsCallRequest(toolName = "some_tool")
        val response = server.handleRequest(request, "session-tools-call-msg")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(-32601) // METHOD_NOT_FOUND
        // Message should indicate tools will be implemented in C3
        val msg = error.error.message.lowercase()
        assertThat(msg.contains("c3") || msg.contains("not implemented")).isTrue()
    }

    // ------------------------------------------------------------------------
    // resources/* handlers
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestResourcesListReturnsEmptyList() {
        server.handleRequest(makeInitializeRequest(), "session-resources-list")

        val request = makeResourcesListRequest()
        val response = server.handleRequest(request, "session-resources-list")

        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success
        val result = Json.Default.decodeFromString<Map<String, Any>>(
            Json.Default.encodeToString(success.result)
        )
        assertThat((result["resources"] as List<*>).isEmpty()).isTrue()
    }

    @Test
    fun handleRequestResourcesReadReturnsErrorInternalError() {
        server.handleRequest(makeInitializeRequest(), "session-resources-read")

        val request = makeResourcesReadRequest()
        val response = server.handleRequest(request, "session-resources-read")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(McpErrorCode.INTERNAL_ERROR)
        assertThat(error.error.message).contains("Resources not implemented")
    }

    // ------------------------------------------------------------------------
    // prompts/* handlers
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestPromptsListReturnsEmptyList() {
        server.handleRequest(makeInitializeRequest(), "session-prompts-list")

        val request = makePromptsListRequest()
        val response = server.handleRequest(request, "session-prompts-list")

        assertThat(response is JsonRpcResponse.Success).isTrue()
        val success = response as JsonRpcResponse.Success
        val result = Json.Default.decodeFromString<Map<String, Any>>(
            Json.Default.encodeToString(success.result)
        )
        assertThat((result["prompts"] as List<*>).isEmpty()).isTrue()
    }

    @Test
    fun handleRequestPromptsGetReturnsErrorInternalError() {
        server.handleRequest(makeInitializeRequest(), "session-prompts-get")

        val request = makePromptsGetRequest()
        val response = server.handleRequest(request, "session-prompts-get")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(McpErrorCode.INTERNAL_ERROR)
        assertThat(error.error.message).contains("Prompts not implemented")
    }

    // ------------------------------------------------------------------------
    // Unknown method
    // ------------------------------------------------------------------------

    @Test
    fun handleRequestUnknownMethodReturnsErrorMethodNotFound() {
        val request = JsonRpcRequest(
            id = kotlinx.serialization.json.JsonPrimitive("unknown-1"),
            method = "unknown/method",
            params = kotlinx.serialization.json.JsonNull
        )
        val response = server.handleRequest(request, "session-unknown")

        assertThat(response is JsonRpcResponse.Error).isTrue()
        val error = response as JsonRpcResponse.Error
        assertThat(error.error.code).isEqualTo(McpErrorCode.METHOD_NOT_FOUND)
        assertThat(error.error.message).contains("Method not found: unknown/method")
    }

    // ------------------------------------------------------------------------
    // Session management (mcp-session-id)
    // ------------------------------------------------------------------------

    @Test
    fun sessionsAreCreatedAutomaticallyWithUniqueId() {
        val request = makeInitializeRequest()
        val sessionId = "custom-session-id-123"

        server.handleRequest(request, sessionId)

        val session = server.sessions[sessionId]
        assertThat(session).isNotNull()
        assertThat(session!!.id).isEqualTo(sessionId)
    }

    @Test
    fun multipleRequestsInSameSessionShareState() {
        val sessionId = "shared-session"

        // Initialize
        server.handleRequest(makeInitializeRequest(), sessionId)

        // Tools/list
        server.handleRequest(makeToolsListRequest(), sessionId)

        // Tools/call
        server.handleRequest(makeToolsCallRequest(), sessionId)

        val session = server.sessions[sessionId]
        assertThat(session).isNotNull()
        assertThat(session!!.initialized).isTrue()
    }

    @Test
    fun differentSessionsHaveIndependentState() {
        server.handleRequest(makeInitializeRequest(), "session-a")
        server.handleRequest(makeInitializeRequest(), "session-b")

        val sessionA = server.sessions["session-a"]
        val sessionB = server.sessions["session-b"]

        assertThat(sessionA).isNotNull()
        assertThat(sessionB).isNotNull()
        assertThat(sessionA).isNotSameInstanceAs(sessionB)
    }

    // ------------------------------------------------------------------------
    // SessionState
    // ------------------------------------------------------------------------

    @Test
    fun sessionStateStoresClientCapabilities() {
        val request = makeInitializeRequest()
        server.handleRequest(request, "session-caps")

        val session = server.sessions["session-caps"]
        assertThat(session).isNotNull()
        assertThat(session!!.clientCapabilities).isNotEmpty()
    }

    // ------------------------------------------------------------------------
    // SSE Session management
    // ------------------------------------------------------------------------

    @Test
    fun registerSseSessionAndUnregisterSseSessionWork() {
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        server.registerSseSession("sse-test", channel)
        assertThat(server.sseSessions["sse-test"]).isNotNull()

        server.unregisterSseSession("sse-test")
        assertThat(server.sseSessions["sse-test"]).isNull()
        assertThat(channel.isClosedForReceive).isTrue()
    }

    @Test
    fun terminateSessionRemovesSessionAndSse() {
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        server.registerSseSession("terminate-test", channel)
        server.handleRequest(makeInitializeRequest(), "terminate-test")

        server.terminateSession("terminate-test")

        assertThat(server.sessions["terminate-test"]).isNull()
        assertThat(server.sseSessions["terminate-test"]).isNull()
    }

    // ------------------------------------------------------------------------
    // broadcastNotification
    // ------------------------------------------------------------------------

    @Test
    fun broadcastNotificationSendsToAllSseSessions() = runTest {
        val channel1 = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val channel2 = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        server.registerSseSession("bcast-1", channel1)
        server.registerSseSession("bcast-2", channel2)

        server.broadcastNotification("test/event", mapOf("data" to "value"))

        // Verify both channels received the event
        val event1 = channel1.receive()
        val event2 = channel2.receive()

        assertThat(event1).contains("event: test/event")
        assertThat(event1).contains("data:")
        assertThat(event2).contains("event: test/event")
        assertThat(event2).contains("data:")

        channel1.close()
        channel2.close()
    }

    // ------------------------------------------------------------------------
    // requestCounter
    // ------------------------------------------------------------------------

    @Test
    fun requestCounterIncrementsOnEachRequest() {
        val initial = server.requestCounter.get()

        server.handleRequest(makeInitializeRequest(), "counter-1")
        server.handleRequest(makeToolsListRequest(), "counter-2")

        assertThat(server.requestCounter.get()).isEqualTo(initial + 2)
    }
}