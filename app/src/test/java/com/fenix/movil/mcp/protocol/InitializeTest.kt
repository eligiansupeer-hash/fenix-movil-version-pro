// ============================================================================
// InitializeTest.kt — verificación de C2 (gate: testDebugUnitTest --tests '*Initialize*')
// Cobertura: InitializeRequest/Response, ClientCapabilities, ServerCapabilities, serialización
// ============================================================================

package com.fenix.movil.mcp.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class InitializeTest {

    private val json = Json.Default

    // ------------------------------------------------------------------------
    // InitializeRequest
    // ------------------------------------------------------------------------

    @Test
    fun initializeRequestSerializesWithRequiredFields() {
        val request = InitializeRequest(
            protocolVersion = "2025-06-18",
            capabilities = ClientCapabilities(),
            clientInfo = ClientInfo(name = "test-client", version = "1.0.0")
        )
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<InitializeRequest>(encoded)

        assertThat(decoded.protocolVersion).isEqualTo("2025-06-18")
        assertThat(decoded.capabilities).isNotNull()
        assertThat(decoded.clientInfo).isNotNull()
        assertThat(decoded.clientInfo?.name).isEqualTo("test-client")
        assertThat(decoded.clientInfo?.version).isEqualTo("1.0.0")
    }

    @Test
    fun initializeRequestSerializesOnlyProtocolVersionWhenOptionalNull() {
        val request = InitializeRequest(protocolVersion = "2025-06-18")
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<InitializeRequest>(encoded)

        assertThat(decoded.protocolVersion).isEqualTo("2025-06-18")
        assertThat(decoded.capabilities).isNull()
        assertThat(decoded.clientInfo).isNull()
    }

    @Test
    fun initializeRequestDeserializesFromCompleteJson() {
        val jsonStr = """
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {
                    "roots": {"listChanged": true},
                    "sampling": {},
                    "elicitation": {}
                },
                "clientInfo": {"name": "TestClient", "version": "2.0.0"}
            }
        """.trimIndent()
        val decoded = json.decodeFromString<InitializeRequest>(jsonStr)

        assertThat(decoded.protocolVersion).isEqualTo("2025-06-18")
        assertThat(decoded.capabilities).isNotNull()
        assertThat(decoded.capabilities?.roots?.listChanged).isTrue()
        assertThat(decoded.capabilities?.sampling).isNotNull()
        assertThat(decoded.capabilities?.elicitation).isNotNull()
        assertThat(decoded.clientInfo?.name).isEqualTo("TestClient")
        assertThat(decoded.clientInfo?.version).isEqualTo("2.0.0")
    }

    // ------------------------------------------------------------------------
    // ClientCapabilities
    // ------------------------------------------------------------------------

    @Test
    fun clientCapabilitiesSerializesRootsWithListChanged() {
        val caps = ClientCapabilities(roots = RootsCapability(listChanged = true))
        val encoded = json.encodeToString(caps)
        val decoded = json.decodeFromString<ClientCapabilities>(encoded)

        assertThat(decoded.roots).isNotNull()
        assertThat(decoded.roots?.listChanged).isTrue()
    }

    @Test
    fun clientCapabilitiesSerializesSamplingAndElicitationAsEmptyObjects() {
        val caps = ClientCapabilities(
            sampling = SamplingCapability,
            elicitation = ElicitationCapability
        )
        val encoded = json.encodeToString(caps)
        val decoded = json.decodeFromString<ClientCapabilities>(encoded)

        assertThat(decoded.sampling).isNotNull()
        assertThat(decoded.elicitation).isNotNull()
    }

    @Test
    fun rootsCapabilityDefaultListChangedIsFalse() {
        val roots = RootsCapability()
        assertThat(roots.listChanged).isFalse()

        val encoded = json.encodeToString(roots)
        val decoded = json.decodeFromString<RootsCapability>(encoded)
        assertThat(decoded.listChanged).isFalse()
    }

    @Test
    fun samplingCapabilityAndElicitationCapabilityAreSingletonObjects() {
        assertThat(SamplingCapability).isSameInstanceAs(SamplingCapability)
        assertThat(ElicitationCapability).isSameInstanceAs(ElicitationCapability)

        val encodedSampling = json.encodeToString(SamplingCapability)
        val encodedElicitation = json.encodeToString(ElicitationCapability)

        // Should serialize as empty objects {}
        assertThat(encodedSampling).isEqualTo("{}")
        assertThat(encodedElicitation).isEqualTo("{}")
    }

    // ------------------------------------------------------------------------
    // ClientInfo
    // ------------------------------------------------------------------------

    @Test
    fun clientInfoSerializesNameAndVersion() {
        val info = ClientInfo(name = "MyMCPClient", version = "3.1.4")
        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<ClientInfo>(encoded)

        assertThat(decoded.name).isEqualTo("MyMCPClient")
        assertThat(decoded.version).isEqualTo("3.1.4")
    }

    // ------------------------------------------------------------------------
    // InitializeResponse
    // ------------------------------------------------------------------------

    @Test
    fun initializeResponseSerializesAllFields() {
        val response = InitializeResponse(
            protocolVersion = "2025-06-18",
            capabilities = InitializeResponse.ServerCapabilities(
                tools = InitializeResponse.ServerCapabilities.ToolsCapability(listChanged = true),
                resources = InitializeResponse.ServerCapabilities.ResourcesCapability(subscribe = true, listChanged = true),
                prompts = InitializeResponse.ServerCapabilities.PromptsCapability(listChanged = true),
                logging = InitializeResponse.ServerCapabilities.LoggingCapability
            ),
            serverInfo = InitializeResponse.ServerInfo(name = "fenix-movil-mcp", version = "1.0.0"),
            instructions = "Welcome to Fenix MCP Server"
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<InitializeResponse>(encoded)

        assertThat(decoded.protocolVersion).isEqualTo("2025-06-18")
        assertThat(decoded.capabilities.tools?.listChanged).isTrue()
        assertThat(decoded.capabilities.resources?.subscribe).isTrue()
        assertThat(decoded.capabilities.resources?.listChanged).isTrue()
        assertThat(decoded.capabilities.prompts?.listChanged).isTrue()
        assertThat(decoded.capabilities.logging).isNotNull()
        assertThat(decoded.serverInfo.name).isEqualTo("fenix-movil-mcp")
        assertThat(decoded.serverInfo.version).isEqualTo("1.0.0")
        assertThat(decoded.instructions).isEqualTo("Welcome to Fenix MCP Server")
    }

    @Test
    fun initializeResponseSerializesOptionalCapabilitiesAsNull() {
        val response = InitializeResponse(
            protocolVersion = "2025-06-18",
            capabilities = InitializeResponse.ServerCapabilities(), // all null
            serverInfo = InitializeResponse.ServerInfo(name = "test", version = "1.0"),
            instructions = null
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<InitializeResponse>(encoded)

        assertThat(decoded.capabilities.tools).isNull()
        assertThat(decoded.capabilities.resources).isNull()
        assertThat(decoded.capabilities.prompts).isNull()
        assertThat(decoded.capabilities.logging).isNull()
        assertThat(decoded.instructions).isNull()
    }

    @Test
    fun initializeResponseDeserializesFromJson() {
        val jsonStr = """
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {
                    "tools": {"listChanged": true},
                    "resources": {"subscribe": true, "listChanged": false},
                    "prompts": {"listChanged": true},
                    "logging": {}
                },
                "serverInfo": {"name": "test-server", "version": "1.0.0"},
                "instructions": "Test instructions"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<InitializeResponse>(jsonStr)

        assertThat(decoded.protocolVersion).isEqualTo("2025-06-18")
        assertThat(decoded.capabilities.tools?.listChanged).isTrue()
        assertThat(decoded.capabilities.resources?.subscribe).isTrue()
        assertThat(decoded.capabilities.resources?.listChanged).isFalse()
        assertThat(decoded.capabilities.prompts?.listChanged).isTrue()
        assertThat(decoded.capabilities.logging).isNotNull()
        assertThat(decoded.serverInfo.name).isEqualTo("test-server")
        assertThat(decoded.instructions).isEqualTo("Test instructions")
    }

    // ------------------------------------------------------------------------
    // ServerCapabilities (alias)
    // ------------------------------------------------------------------------

    @Test
    fun serverCapabilitiesTypealiasWorksAsInitializeResponseServerCapabilities() {
        val caps: ServerCapabilities = InitializeResponse.ServerCapabilities(
            tools = InitializeResponse.ServerCapabilities.ToolsCapability(listChanged = true)
        )
        val encoded = json.encodeToString(caps)
        val decoded = json.decodeFromString<ServerCapabilities>(encoded)

        assertThat(decoded.tools?.listChanged).isTrue()
    }

    // ------------------------------------------------------------------------
    // ServerCapabilities sub-types
    // ------------------------------------------------------------------------

    @Test
    fun toolsCapabilitySerializesListChanged() {
        val cap = InitializeResponse.ServerCapabilities.ToolsCapability(listChanged = true)
        val encoded = json.encodeToString(cap)
        val decoded = json.decodeFromString<InitializeResponse.ServerCapabilities.ToolsCapability>(encoded)

        assertThat(decoded.listChanged).isTrue()
    }

    @Test
    fun resourcesCapabilitySerializesSubscribeAndListChanged() {
        val cap = InitializeResponse.ServerCapabilities.ResourcesCapability(
            subscribe = true,
            listChanged = false
        )
        val encoded = json.encodeToString(cap)
        val decoded = json.decodeFromString<InitializeResponse.ServerCapabilities.ResourcesCapability>(encoded)

        assertThat(decoded.subscribe).isTrue()
        assertThat(decoded.listChanged).isFalse()
    }

    @Test
    fun promptsCapabilitySerializesListChanged() {
        val cap = InitializeResponse.ServerCapabilities.PromptsCapability(listChanged = true)
        val encoded = json.encodeToString(cap)
        val decoded = json.decodeFromString<InitializeResponse.ServerCapabilities.PromptsCapability>(encoded)

        assertThat(decoded.listChanged).isTrue()
    }

    @Test
    fun loggingCapabilityIsSingletonObject() {
        val cap = InitializeResponse.ServerCapabilities.LoggingCapability
        val encoded = json.encodeToString(cap)
        assertThat(encoded).isEqualTo("{}")
    }

    // ------------------------------------------------------------------------
    // ServerInfo
    // ------------------------------------------------------------------------

    @Test
    fun serverInfoSerializesNameAndVersion() {
        val info = InitializeResponse.ServerInfo(name = "test", version = "1.0")
        val encoded = json.encodeToString(info)
        val decoded = json.decodeFromString<InitializeResponse.ServerInfo>(encoded)

        assertThat(decoded.name).isEqualTo("test")
        assertThat(decoded.version).isEqualTo("1.0")
    }

    // ------------------------------------------------------------------------
    // Round-trip encoding/decoding
    // ------------------------------------------------------------------------

    @Test
    fun roundTripInitializeRequestJsonInitializeRequest() {
        val original = InitializeRequest(
            protocolVersion = "2025-06-18",
            capabilities = ClientCapabilities(
                roots = RootsCapability(listChanged = true),
                sampling = SamplingCapability
            ),
            clientInfo = ClientInfo(name = "round-trip-client", version = "1.0.0")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<InitializeRequest>(encoded)

        assertThat(decoded.protocolVersion).isEqualTo(original.protocolVersion)
        assertThat(decoded.capabilities?.roots?.listChanged).isEqualTo(original.capabilities?.roots?.listChanged)
        assertThat(decoded.capabilities?.sampling).isNotNull()
        assertThat(decoded.clientInfo?.name).isEqualTo(original.clientInfo?.name)
        assertThat(decoded.clientInfo?.version).isEqualTo(original.clientInfo?.version)
    }

    @Test
    fun roundTripInitializeResponseJsonInitializeResponse() {
        val original = InitializeResponse(
            protocolVersion = "2025-06-18",
            capabilities = InitializeResponse.ServerCapabilities(
                tools = InitializeResponse.ServerCapabilities.ToolsCapability(listChanged = true),
                resources = InitializeResponse.ServerCapabilities.ResourcesCapability(subscribe = true, listChanged = true)
            ),
            serverInfo = InitializeResponse.ServerInfo(name = "fenix-movil-mcp", version = "1.0.0"),
            instructions = "Test instructions"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<InitializeResponse>(encoded)

        assertThat(decoded.protocolVersion).isEqualTo(original.protocolVersion)
        assertThat(decoded.capabilities.tools?.listChanged).isEqualTo(original.capabilities.tools?.listChanged)
        assertThat(decoded.capabilities.resources?.subscribe).isEqualTo(original.capabilities.resources?.subscribe)
        assertThat(decoded.capabilities.resources?.listChanged).isEqualTo(original.capabilities.resources?.listChanged)
        assertThat(decoded.serverInfo.name).isEqualTo(original.serverInfo.name)
        assertThat(decoded.serverInfo.version).isEqualTo(original.serverInfo.version)
        assertThat(decoded.instructions).isEqualTo(original.instructions)
    }
}