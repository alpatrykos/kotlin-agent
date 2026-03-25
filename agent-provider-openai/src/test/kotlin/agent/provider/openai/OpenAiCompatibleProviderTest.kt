package agent.provider.openai

import agent.core.AgentJson
import agent.core.AssistantMessage
import agent.core.ProviderEvent
import agent.core.SessionContext
import agent.core.SystemMessage
import agent.core.ToolCallRequest
import agent.core.ToolResultMessage
import agent.core.ToolSpec
import agent.core.UserMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    @Test
    fun `posts responses request and streams text plus tool calls`() {
        runBlocking {
            val requestBodies = mutableListOf<JsonObject>()
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/responses") { exchange ->
                requestBodies += AgentJson.parseToJsonElement(
                    exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
                ).jsonObject
                val body = buildString {
                    appendLine("event: response.output_text.delta")
                    appendLine("""data: {"type":"response.output_text.delta","delta":"Hello "}""")
                    appendLine()
                    appendLine("event: response.output_item.added")
                    appendLine("""data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file","arguments":"{\"path\":\""}}""")
                    appendLine()
                    appendLine("event: response.function_call_arguments.delta")
                    appendLine("""data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"README.md\"}"}""")
                    appendLine()
                    appendLine("event: response.output_item.done")
                    appendLine("""data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file","arguments":"{\"path\":\"README.md\"}"}}""")
                    appendLine()
                    appendLine("event: response.completed")
                    appendLine("""data: {"type":"response.completed","response":{"status":"completed"}}""")
                    appendLine()
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { output ->
                    output.write(body.toByteArray())
                }
            }
            server.start()

            try {
                val provider = OpenAiCompatibleProvider(
                    config = OpenAiCompatibleConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        apiKey = "",
                        model = "test-model",
                    ),
                    httpClient = HttpClient.newBuilder().build(),
                )

                val events = provider.streamTurn(
                    context = sessionContext(),
                    conversation = listOf(SystemMessage("system"), UserMessage("inspect the repo")),
                    tools = listOf(
                        ToolSpec(
                            name = "read_file",
                            description = "Read a file",
                            parameters = buildJsonObject { put("type", "object") },
                        ),
                    ),
                ).toList()

                val requestBody = requestBodies.single()
                assertFalse("messages" in requestBody)
                assertEquals("test-model", requestBody["model"]?.jsonPrimitive?.content)
                assertEquals(true, requestBody["stream"]?.jsonPrimitive?.content?.toBooleanStrict())
                assertEquals(false, requestBody["store"]?.jsonPrimitive?.content?.toBooleanStrict())
                val input = requestBody["input"]?.jsonArray ?: error("missing input")
                assertEquals(2, input.size)
                assertEquals("system", input[0].jsonObject["role"]?.jsonPrimitive?.content)
                assertEquals("user", input[1].jsonObject["role"]?.jsonPrimitive?.content)
                val tools = requestBody["tools"]?.jsonArray ?: error("missing tools")
                assertEquals("function", tools.first().jsonObject["type"]?.jsonPrimitive?.content)
                assertEquals("read_file", tools.first().jsonObject["name"]?.jsonPrimitive?.content)

                assertEquals("Hello ", (events[0] as ProviderEvent.TextDelta).delta)
                val toolCalls = (events[1] as ProviderEvent.ToolCallsPrepared).toolCalls
                assertEquals(1, toolCalls.size)
                assertEquals("call_1", toolCalls.first().id)
                assertEquals("read_file", toolCalls.first().name)
                assertEquals("README.md", toolCalls.first().arguments["path"]?.jsonPrimitive?.content)
                assertEquals("completed", (events.last() as ProviderEvent.Completed).finishReason)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `encodes assistant tool calls and tool outputs as responses input items`() {
        runBlocking {
            val requestBodies = mutableListOf<JsonObject>()
            val server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/responses") { exchange ->
                requestBodies += AgentJson.parseToJsonElement(
                    exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
                ).jsonObject
                val body = buildString {
                    appendLine("event: response.output_text.delta")
                    appendLine("""data: {"type":"response.output_text.delta","delta":"done"}""")
                    appendLine()
                    appendLine("event: response.completed")
                    appendLine("""data: {"type":"response.completed","response":{"status":"completed"}}""")
                    appendLine()
                }
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { output -> output.write(body.toByteArray()) }
            }
            server.start()

            try {
                val provider = OpenAiCompatibleProvider(
                    config = OpenAiCompatibleConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        apiKey = "",
                        model = "test-model",
                    ),
                    httpClient = HttpClient.newBuilder().build(),
                )

                provider.streamTurn(
                    context = sessionContext(),
                    conversation = listOf(
                        UserMessage("inspect"),
                        AssistantMessage(
                            content = "",
                            toolCalls = listOf(
                                ToolCallRequest(
                                    id = "call_1",
                                    name = "read_file",
                                    arguments = buildJsonObject { put("path", "README.md") },
                                ),
                            ),
                        ),
                        ToolResultMessage(
                            toolCallId = "call_1",
                            toolName = "read_file",
                            content = "README body",
                        ),
                    ),
                    tools = emptyList(),
                ).toList()

                val input = requestBodies.single()["input"]?.jsonArray ?: error("missing input")
                assertEquals(3, input.size)
                assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
                assertEquals("function_call", input[1].jsonObject["type"]?.jsonPrimitive?.content)
                assertEquals("call_1", input[1].jsonObject["call_id"]?.jsonPrimitive?.content)
                assertEquals("read_file", input[1].jsonObject["name"]?.jsonPrimitive?.content)
                assertEquals("""{"path":"README.md"}""", input[1].jsonObject["arguments"]?.jsonPrimitive?.content)
                assertEquals("function_call_output", input[2].jsonObject["type"]?.jsonPrimitive?.content)
                assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)
                assertEquals("README body", input[2].jsonObject["output"]?.jsonPrimitive?.content)
            } finally {
                server.stop(0)
            }
        }
    }

    private fun sessionContext(): SessionContext {
        return SessionContext(
            sessionId = "session-1",
            workspaceRoot = Files.createTempDirectory("provider-workspace"),
            storageRoot = Files.createTempDirectory("provider-storage"),
            artifactRoot = Files.createTempDirectory("provider-artifacts"),
            systemPrompt = "system",
            shellTimeoutMillis = 1_000,
            maxToolOutputChars = 10_000,
        )
    }
}
