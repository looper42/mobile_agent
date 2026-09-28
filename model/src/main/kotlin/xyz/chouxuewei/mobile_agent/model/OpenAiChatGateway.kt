package xyz.chouxuewei.mobile_agent.model

import xyz.chouxuewei.mobile_agent.core.localizedText
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import xyz.chouxuewei.mobile_agent.core.*

/** 与设备动作协议完全独立的文本 SSE 适配器。取消 Flow 会同时关闭 Call 和响应体。 */
class OpenAiChatGateway(
    private val config: ModelConfig,
    private val client: OkHttpClient,
) : ChatModelGateway {
    override fun stream(request: ChatRequest): Flow<ModelEvent> = callbackFlow {
        val model = config.model?.takeIf { it.isNotBlank() }
        if (model == null) {
            send(
                ModelEvent.Error(
                    localizedText(
                        "请在模型设置中填写模型 ID",
                        "Enter a model ID in Model settings."
                    )
                )
            ); close(); return@callbackFlow
        }
        val base = config.baseUrl.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
        val requestBody = StreamingChatRequestBody(model, request, config.reasoningEffortField)
        val requestStartedAt = System.currentTimeMillis()
        AgentLog.i("Model") {
            "request_start model=$model messages=${request.messages.size} tools=${request.tools.size} images=${request.messages.sumOf { it.images.size }} estimated_payload_bytes=${requestBody.estimatedBytes} max_output=${request.maxOutputTokens}"
        }
        val call = client.newCall(
            Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "text/event-stream")
                .post(requestBody)
                .build()
        )
        call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    AgentLog.e("Model", e) {
                        "request_failed model=$model cancelled=${call.isCanceled()} duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                    }
                    if (!call.isCanceled()) trySend(
                        ModelEvent.Error(
                            localizedText(
                                "无法连接模型服务，请检查网络和服务地址",
                                "Could not connect to the model service. Check your network and service URL."
                            )
                        )
                    )
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    AgentLog.i("Model") { "response_headers model=$model code=${response.code}" }
                    launch(Dispatchers.IO) {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    val hasImages =
                                        request.messages.any { turn -> turn.images.isNotEmpty() }
                                    val message = when {
                                        it.code == 401 || it.code == 403 ->
                                            localizedText(
                                                "API 密钥无效或没有访问权限，请检查模型设置",
                                                "The API key is invalid or lacks access. Check Model settings."
                                            )

                                        it.code == 404 ->
                                            localizedText(
                                                "没有找到该服务或模型，请检查服务地址和模型 ID",
                                                "The service or model was not found. Check the service URL and model ID."
                                            )

                                        it.code == 408 -> localizedText(
                                            "请求超时，请稍后重试",
                                            "The request timed out. Please try again later."
                                        )

                                        it.code == 413 -> localizedText(
                                            "本次发送的内容过大，请减少附件或缩短输入后重试",
                                            "This message is too large. Remove attachments or shorten the input and try again."
                                        )

                                        it.code == 429 ->
                                            localizedText(
                                                "请求过于频繁或账户额度不足，请稍后重试或检查账户额度",
                                                "Too many requests or insufficient account quota. Try again later or check your quota."
                                            )

                                        it.code in 500..599 -> localizedText(
                                            "模型服务暂时不可用，请稍后重试",
                                            "The model service is temporarily unavailable. Please try again later."
                                        )

                                        hasImages && it.code in 400..422 ->
                                            localizedText(
                                                "当前模型不支持图片理解，请更换支持图片的模型",
                                                "The current model does not support image understanding. Choose a vision-capable model."
                                            )

                                        else -> localizedText(
                                            "模型服务拒绝了本次请求，请检查模型设置后重试",
                                            "The model service rejected this request. Check Model settings and try again."
                                        )
                                    }
                                    // 保留 HTTP 状态码便于用户排查，同时不回显可能包含密钥或隐私的服务端响应正文。
                                    send(ModelEvent.Error("$message (HTTP ${it.code})")); return@use
                                }
                                val source = it.body?.source() ?: throw IOException("empty body")
                                val data = StringBuilder()
                                var done = false
                                var finish: String? = null

                                data class PendingCall(
                                    val id: StringBuilder = StringBuilder(),
                                    val name: StringBuilder = StringBuilder(),
                                    val arguments: StringBuilder = StringBuilder()
                                )

                                val pendingCalls = linkedMapOf<Int, PendingCall>()
                                var responseTextCharacters = 0L
                                var reasoningCharacters = 0L
                                var toolArgumentCharacters = 0L
                                suspend fun dispatch() {
                                    val value = data.toString().trim(); data.clear()
                                    if (value.isEmpty()) return
                                    if (value == "[DONE]") {
                                        done = true; return
                                    }
                                    val json = Json.parseToJsonElement(value).jsonObject
                                    if (json["error"] != null) throw IOException("server error")
                                    json["usage"]?.takeUnless { u -> u is JsonNull }?.jsonObject?.let { usage ->
                                        send(
                                            ModelEvent.Usage(
                                                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                                                usage["completion_tokens"]?.jsonPrimitive?.intOrNull
                                            )
                                        )
                                    }
                                    val choice =
                                        json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                            ?: return
                                    val delta = choice["delta"]?.jsonObject
                                    delta?.get("tool_calls")?.jsonArray?.forEach { element ->
                                        val value = element.jsonObject
                                        val index = value["index"]?.jsonPrimitive?.intOrNull
                                            ?: pendingCalls.size
                                        require(index in 0 until MAX_TOOL_CALLS) {
                                            localizedText("模型返回的工具调用过多", "The model returned too many tool calls.")
                                        }
                                        val pending = pendingCalls.getOrPut(index) {
                                            require(pendingCalls.size < MAX_TOOL_CALLS) {
                                                localizedText("模型返回的工具调用过多", "The model returned too many tool calls.")
                                            }
                                            PendingCall()
                                        }
                                        value["id"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                            require(pending.id.length + chunk.length <= MAX_TOOL_ID_CHARACTERS)
                                            pending.id.append(chunk)
                                        }
                                        value["function"]?.jsonObject?.let { function ->
                                            function["name"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                                // tool_calls 的 name 与 arguments 都是 delta 片段，必须按到达顺序原样拼接。
                                                require(pending.name.length + chunk.length <= MAX_TOOL_NAME_CHARACTERS)
                                                pending.name.append(chunk)
                                            }
                                            function["arguments"]?.jsonPrimitive?.contentOrNull?.let { chunk ->
                                                require(pending.arguments.length + chunk.length <= MAX_TOOL_ARGUMENT_CHARACTERS) {
                                                    localizedText("模型返回的工具参数过大", "The model returned oversized tool arguments.")
                                                }
                                                toolArgumentCharacters += chunk.length
                                                require(toolArgumentCharacters <= MAX_TOTAL_TOOL_ARGUMENT_CHARACTERS) {
                                                    localizedText("模型返回的工具参数总量过大", "The model returned too much tool argument data.")
                                                }
                                                pending.arguments.append(chunk)
                                            }
                                        }
                                    }
                                    listOf(
                                        "reasoning_content",
                                        "reasoning",
                                        "thinking"
                                    ).firstNotNullOfOrNull { key ->
                                        (delta?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(
                                            String::isNotEmpty
                                        )
                                    }?.let { text ->
                                        reasoningCharacters += text.length
                                        require(reasoningCharacters <= MAX_REASONING_CHARACTERS) {
                                            localizedText("模型思考内容过长", "The model reasoning exceeded the response limit.")
                                        }
                                        send(ModelEvent.ReasoningDelta(text))
                                    }
                                    (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                                        ?.takeIf(String::isNotEmpty)
                                        ?.let { text ->
                                            responseTextCharacters += text.length
                                            require(responseTextCharacters <= MAX_RESPONSE_TEXT_CHARACTERS) {
                                                localizedText("模型回复过长", "The model response exceeded the response limit.")
                                            }
                                            send(ModelEvent.TextDelta(text))
                                        }
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                                        finish = reason
                                    }
                                }
                                // 在解码之前限制单行字节数，避免异常服务用一个永不换行的响应占满内存。
                                while (!done) {
                                    val line = source.readBoundedUtf8Line(MAX_SSE_LINE_BYTES) ?: break
                                    when {
                                        line.isEmpty() -> dispatch()
                                        line.startsWith("data:") -> {
                                            if (data.isNotEmpty()) data.append('\n'); data.append(
                                                line.removePrefix("data:").trimStart()
                                            )
                                        }
                                    }
                                    check(data.length <= MAX_SSE_EVENT_CHARACTERS) {
                                        localizedText(
                                            "模型事件过大",
                                            "The model event is too large."
                                        )
                                    }
                                }
                                if (data.isNotEmpty()) dispatch()
                                pendingCalls.values.forEach { pending ->
                                    if (pending.id.isNotBlank() && pending.name.isNotBlank()) {
                                        send(
                                            ModelEvent.ToolCall(
                                                RequestedToolCall(
                                                    pending.id.toString(),
                                                    pending.name.toString(),
                                                    pending.arguments.toString().ifBlank { "{}" },
                                                )
                                            )
                                        )
                                    }
                                }
                                AgentLog.i("Model") {
                                    "response_complete model=$model finish=${finish ?: if (done) "stop" else "interrupted"} tool_calls=${pendingCalls.size} duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                                }
                                if (!done && finish == null) send(
                                    ModelEvent.Error(
                                        localizedText(
                                            "回复意外中断，已生成的内容已保留",
                                            "The response was interrupted. Generated content was preserved."
                                        )
                                    )
                                )
                                else if (finish != null && finish !in setOf(
                                        "stop",
                                        "length",
                                        "tool_calls"
                                    )
                                ) send(
                                    ModelEvent.Error(
                                        localizedText(
                                            "模型提前结束，已生成的内容已保留",
                                            "The model ended early. Generated content was preserved."
                                        )
                                    )
                                )
                                else send(ModelEvent.Completed(finish ?: "stop"))
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            AgentLog.e("Model", error) {
                                "response_failed model=$model duration_ms=${System.currentTimeMillis() - requestStartedAt}"
                            }
                            if (!call.isCanceled()) send(
                                ModelEvent.Error(
                                    localizedText(
                                        "回复意外中断，已生成的内容已保留",
                                        "The response was interrupted. Generated content was preserved."
                                    )
                                )
                            )
                        } finally { response.close(); close() }
                    }
                }
        })
        awaitClose { call.cancel() }
    }
}

/**
 * 共享客户端复用连接池和 TLS 会话。readTimeout 同时约束响应首字节与连续无数据时间，
 * callTimeout 保持关闭，正常的长回复不会因为总时长被截断；用户停止任务仍会取消 Call。
 */
fun streamingChatHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(STREAM_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .build()

private fun BufferedSource.readBoundedUtf8Line(maxBytes: Long): String? {
    val newline = indexOf('\n'.code.toByte(), 0, maxBytes + 1)
    if (newline >= 0) return readUtf8Line()
    if (request(maxBytes + 1)) throw IOException(
        localizedText("模型事件单行过大", "A model event line exceeded the allowed size."),
    )
    // EOF with a final non-newline-terminated line is valid as long as request() proved it bounded.
    return readUtf8Line()
}

private const val STREAM_IDLE_TIMEOUT_SECONDS = 90L
private const val MAX_SSE_LINE_BYTES = 1_048_576L
private const val MAX_SSE_EVENT_CHARACTERS = 1_000_000
private const val MAX_RESPONSE_TEXT_CHARACTERS = 2_000_000L
private const val MAX_REASONING_CHARACTERS = 2_000_000L
private const val MAX_TOOL_CALLS = 64
private const val MAX_TOOL_ID_CHARACTERS = 512
private const val MAX_TOOL_NAME_CHARACTERS = 256
private const val MAX_TOOL_ARGUMENT_CHARACTERS = 1_000_000
private const val MAX_TOTAL_TOOL_ARGUMENT_CHARACTERS = 2_000_000L
