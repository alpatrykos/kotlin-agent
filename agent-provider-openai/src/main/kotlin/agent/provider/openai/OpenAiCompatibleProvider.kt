package agent.provider.openai

import agent.core.AgentJson
import agent.core.AssistantMessage
import agent.core.ConversationItem
import agent.core.ModelProvider
import agent.core.ProviderEvent
import agent.core.SessionContext
import agent.core.SystemMessage
import agent.core.ToolCallRequest
import agent.core.ToolResultMessage
import agent.core.ToolSpec
import agent.core.UserMessage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = null,
)

class OpenAiCompatibleProvider(
    private val config: OpenAiCompatibleConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
) : ModelProvider {
    override fun streamTurn(
        context: SessionContext,
        conversation: List<ConversationItem>,
        tools: List<ToolSpec>,
    ): Flow<ProviderEvent> = flow {
        val requestBody = ResponsesRequest(
            model = config.model,
            input = conversation.flatMap(::toResponseInputItems),
            tools = tools.takeIf { it.isNotEmpty() }?.map(::toResponseTool),
            stream = true,
            store = false,
            temperature = config.temperature,
        )
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${config.baseUrl.trimEnd('/')}/responses"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(AgentJson.encodeToString(requestBody)))
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        val response = httpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() !in 200..299) {
            val body = response.body().use { input ->
                input.readAllBytes().toString(StandardCharsets.UTF_8)
            }
            error("Provider request failed with ${response.statusCode()}: $body")
        }

        BufferedReader(InputStreamReader(response.body(), StandardCharsets.UTF_8)).use { reader ->
            val toolCallAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
            var completionStatus: String? = null

            parseSseStream(reader) { payload ->
                if (payload == "[DONE]") {
                    return@parseSseStream
                }
                val event = AgentJson.parseToJsonElement(payload).jsonObject
                when (event.string("type")) {
                    "response.output_text.delta" -> {
                        event.string("delta")?.let { emit(ProviderEvent.TextDelta(it)) }
                    }

                    "response.output_item.added" -> {
                        val outputIndex = event.int("output_index") ?: return@parseSseStream
                        val item = event.objectValue("item") ?: return@parseSseStream
                        if (item.string("type") == "function_call") {
                            val accumulator = toolCallAccumulators.getOrPut(outputIndex) { ToolCallAccumulator() }
                            accumulator.callId = item.string("call_id") ?: accumulator.callId
                            accumulator.name = item.string("name") ?: accumulator.name
                            item.string("arguments")?.let(accumulator::setArgumentsIfEmpty)
                        }
                    }

                    "response.function_call_arguments.delta" -> {
                        val outputIndex = event.int("output_index") ?: return@parseSseStream
                        val delta = event.string("delta") ?: return@parseSseStream
                        val accumulator = toolCallAccumulators.getOrPut(outputIndex) { ToolCallAccumulator() }
                        accumulator.arguments.append(delta)
                    }

                    "response.function_call_arguments.done" -> {
                        val outputIndex = event.int("output_index") ?: return@parseSseStream
                        val accumulator = toolCallAccumulators.getOrPut(outputIndex) { ToolCallAccumulator() }
                        val item = event.objectValue("item")
                        accumulator.callId = item?.string("call_id") ?: event.string("call_id") ?: accumulator.callId
                        accumulator.name = item?.string("name") ?: event.string("name") ?: accumulator.name
                        item?.string("arguments")?.let(accumulator::setArguments)
                            ?: event.string("arguments")?.let(accumulator::setArguments)
                    }

                    "response.output_item.done" -> {
                        val outputIndex = event.int("output_index") ?: return@parseSseStream
                        val item = event.objectValue("item") ?: return@parseSseStream
                        if (item.string("type") == "function_call") {
                            val accumulator = toolCallAccumulators.getOrPut(outputIndex) { ToolCallAccumulator() }
                            accumulator.callId = item.string("call_id") ?: accumulator.callId
                            accumulator.name = item.string("name") ?: accumulator.name
                            item.string("arguments")?.let(accumulator::setArguments)
                        }
                    }

                    "response.completed" -> {
                        completionStatus = event.objectValue("response")?.string("status") ?: "completed"
                    }
                }
            }

            if (toolCallAccumulators.isNotEmpty()) {
                emit(
                    ProviderEvent.ToolCallsPrepared(
                        toolCallAccumulators.entries.sortedBy { it.key }.map { (_, accumulator) ->
                            ToolCallRequest(
                                id = accumulator.callId ?: "call_${UUID.randomUUID()}",
                                name = accumulator.name ?: error("Missing function name in streamed tool call."),
                                arguments = parseArguments(accumulator.arguments.toString()),
                            )
                        },
                    ),
                )
            }
            emit(ProviderEvent.Completed(completionStatus))
        }
    }

    private suspend fun parseSseStream(reader: BufferedReader, onData: suspend (String) -> Unit) {
        val dataLines = mutableListOf<String>()
        while (true) {
            val rawLine = reader.readLine()
            if (rawLine == null) {
                flushSseData(dataLines, onData)
                break
            }
            if (rawLine.isBlank()) {
                flushSseData(dataLines, onData)
                dataLines.clear()
                continue
            }
            if (rawLine.startsWith("data:")) {
                dataLines += rawLine.removePrefix("data:").trimStart()
            }
        }
    }

    private suspend fun flushSseData(dataLines: List<String>, onData: suspend (String) -> Unit) {
        if (dataLines.isNotEmpty()) {
            onData(dataLines.joinToString("\n"))
        }
    }

    private fun parseArguments(arguments: String): JsonObject {
        if (arguments.isBlank()) {
            return JsonObject(emptyMap())
        }
        return AgentJson.parseToJsonElement(arguments).jsonObject
    }

    private fun toResponseTool(spec: ToolSpec): ResponseTool {
        return ResponseTool(
            name = spec.name,
            description = spec.description,
            parameters = spec.parameters,
            strict = true,
        )
    }

    private fun toResponseInputItems(item: ConversationItem): List<JsonObject> {
        return when (item) {
            is SystemMessage -> listOf(messageInput(role = "system", content = item.content))
            is UserMessage -> listOf(messageInput(role = "user", content = item.content))
            is AssistantMessage -> buildList {
                if (item.content.isNotBlank()) {
                    add(messageInput(role = "assistant", content = item.content))
                }
                item.toolCalls.forEach { toolCall ->
                    add(
                        buildJsonObject {
                            put("type", "function_call")
                            put("call_id", toolCall.id)
                            put("name", toolCall.name)
                            put("arguments", AgentJson.encodeToString(JsonObject.serializer(), toolCall.arguments))
                        },
                    )
                }
            }

            is ToolResultMessage -> listOf(
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", item.toolCallId)
                    put("output", item.content)
                },
            )
        }
    }

    private fun messageInput(role: String, content: String): JsonObject {
        return buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }
}

private data class ToolCallAccumulator(
    var callId: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
) {
    fun setArguments(value: String) {
        arguments.setLength(0)
        arguments.append(value)
    }

    fun setArgumentsIfEmpty(value: String) {
        if (arguments.isEmpty()) {
            arguments.append(value)
        }
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<JsonObject>,
    val tools: List<ResponseTool>? = null,
    val stream: Boolean = true,
    val store: Boolean = false,
    val temperature: Double? = null,
)

@Serializable
private data class ResponseTool(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val strict: Boolean = true,
)

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

private fun JsonObject.objectValue(name: String): JsonObject? = this[name]?.jsonObject
