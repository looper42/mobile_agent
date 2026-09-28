package xyz.chouxuewei.mobile_agent.model

import java.io.OutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.chouxuewei.mobile_agent.core.ChatImage
import xyz.chouxuewei.mobile_agent.core.ChatImageLimits
import xyz.chouxuewei.mobile_agent.core.ChatRequest
import xyz.chouxuewei.mobile_agent.core.ChatTurn
import xyz.chouxuewei.mobile_agent.core.localizedText

/** Writes JSON and image Base64 directly to Okio instead of materializing a second full payload. */
internal class StreamingChatRequestBody(
    private val model: String,
    private val request: ChatRequest,
    private val reasoningEffortField: String,
) : RequestBody() {
    val estimatedBytes: Long

    init {
        val images = request.messages.flatMap(ChatTurn::images)
        require(images.size <= ChatImageLimits.MAX_COUNT) {
            localizedText("模型请求中的图片过多", "The model request contains too many images.")
        }
        val imageBytes = images.sumOf { it.bytes.size.toLong() }
        require(imageBytes <= ChatImageLimits.MAX_TOTAL_BYTES) {
            localizedText("模型请求中的图片总量过大", "Images in the model request are too large.")
        }
        require(images.all { it.pixelCount() in 1..ChatImageLimits.MAX_SINGLE_PIXELS } &&
            images.sumOf { it.pixelCount() } <= ChatImageLimits.MAX_TOTAL_PIXELS) {
            localizedText("模型请求中的图片像素总量过大", "Images in the model request contain too many pixels.")
        }
        require(images.all { MIME_TYPE.matches(it.mimeType) }) {
            localizedText("图片格式标识无效", "An image MIME type is invalid.")
        }

        val encodedImageBytes = images.sumOf { image -> ((image.bytes.size.toLong() + 2) / 3) * 4 }
        val textBytes = request.messages.sumOf { turn ->
            turn.content.jsonBytes() + turn.role.jsonBytes() +
                turn.toolCalls.sumOf { call ->
                    call.id.jsonBytes() + call.toolId.jsonBytes() + call.argumentsJson.jsonBytes()
                } + (turn.toolCallId?.jsonBytes() ?: 0) + (turn.name?.jsonBytes() ?: 0)
        } + request.tools.sumOf { tool ->
            tool.id.jsonBytes() + tool.description.jsonBytes() + tool.inputSchema.toByteArray().size.toLong()
        }
        estimatedBytes = encodedImageBytes + textBytes + REQUEST_OVERHEAD_BYTES
        require(estimatedBytes <= MAX_REQUEST_BODY_BYTES) {
            localizedText("模型请求总量过大，请减少图片或缩短输入", "The model request is too large. Remove images or shorten the input.")
        }
    }

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    // The body is intentionally streamed; returning -1 lets OkHttp use chunked transfer encoding.
    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("{\"model\":")
        sink.writeJsonString(model)
        sink.writeUtf8(",\"stream\":true,\"max_tokens\":${request.maxOutputTokens}")
        sink.writeUtf8(",\"max_completion_tokens\":${request.maxOutputTokens}")
        sink.writeUtf8(",\"stream_options\":{\"include_usage\":true},\"tools\":[")
        request.tools.forEachIndexed { index, tool ->
            if (index > 0) sink.writeByte(','.code)
            sink.writeUtf8("{\"type\":\"function\",\"function\":{\"name\":")
            sink.writeJsonString(tool.id)
            sink.writeUtf8(",\"description\":")
            sink.writeJsonString(tool.description)
            sink.writeUtf8(",\"parameters\":")
            sink.writeUtf8(runCatching { Json.parseToJsonElement(tool.inputSchema).toString() }
                .getOrElse { buildJsonObject { put("type", "object") }.toString() })
            sink.writeUtf8("}}")
        }
        sink.writeByte(']'.code)
        request.reasoningEffort?.takeIf(String::isNotBlank)?.let { effort ->
            reasoningEffortField.takeIf(String::isNotBlank)?.let { field ->
                sink.writeByte(','.code)
                sink.writeJsonString(field)
                sink.writeByte(':'.code)
                sink.writeJsonString(effort)
            }
        }
        sink.writeUtf8(",\"messages\":[")
        request.messages.forEachIndexed { index, turn ->
            if (index > 0) sink.writeByte(','.code)
            sink.writeUtf8("{\"role\":")
            sink.writeJsonString(turn.role)
            when {
                turn.role == "assistant" && turn.toolCalls.isNotEmpty() -> writeAssistant(sink, turn)
                turn.role == "user" && turn.images.isNotEmpty() -> writeImageContent(sink, turn)
                else -> {
                    sink.writeUtf8(",\"content\":")
                    sink.writeJsonString(turn.content)
                }
            }
            turn.toolCallId?.let { sink.writeUtf8(",\"tool_call_id\":"); sink.writeJsonString(it) }
            turn.name?.let { sink.writeUtf8(",\"name\":"); sink.writeJsonString(it) }
            sink.writeByte('}'.code)
        }
        sink.writeUtf8("]}")
    }

    private fun writeAssistant(sink: BufferedSink, turn: ChatTurn) {
        sink.writeUtf8(",\"content\":")
        if (turn.content.isEmpty()) sink.writeUtf8("null") else sink.writeJsonString(turn.content)
        sink.writeUtf8(",\"tool_calls\":[")
        turn.toolCalls.forEachIndexed { index, call ->
            if (index > 0) sink.writeByte(','.code)
            sink.writeUtf8("{\"id\":")
            sink.writeJsonString(call.id)
            sink.writeUtf8(",\"type\":\"function\",\"function\":{\"name\":")
            sink.writeJsonString(call.toolId)
            sink.writeUtf8(",\"arguments\":")
            sink.writeJsonString(call.argumentsJson)
            sink.writeUtf8("}}")
        }
        sink.writeByte(']'.code)
    }

    private fun writeImageContent(sink: BufferedSink, turn: ChatTurn) {
        sink.writeUtf8(",\"content\":[")
        var needsComma = false
        if (turn.content.isNotBlank()) {
            sink.writeUtf8("{\"type\":\"text\",\"text\":")
            sink.writeJsonString(turn.content)
            sink.writeByte('}'.code)
            needsComma = true
        }
        turn.images.forEach { image ->
            if (needsComma) sink.writeByte(','.code)
            sink.writeUtf8("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:")
            sink.writeUtf8(image.mimeType)
            sink.writeUtf8(";base64,")
            sink.writeBase64(image.bytes)
            sink.writeUtf8("\",\"detail\":\"auto\"}}")
            needsComma = true
        }
        sink.writeByte(']'.code)
    }

    private fun BufferedSink.writeJsonString(value: String) {
        writeUtf8(JsonPrimitive(value).toString())
    }

    private fun BufferedSink.writeBase64(bytes: ByteArray) {
        val nonClosingOutput = object : OutputStream() {
            override fun write(value: Int) { this@writeBase64.writeByte(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                this@writeBase64.write(buffer, offset, length)
            }
            override fun close() = Unit
        }
        Base64.getEncoder().wrap(nonClosingOutput).use { encoder -> encoder.write(bytes) }
    }

    private fun ChatImage.pixelCount(): Long = width.toLong() * height
    private fun String.jsonBytes(): Long = JsonPrimitive(this).toString().toByteArray().size.toLong()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val MIME_TYPE = Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")
        const val REQUEST_OVERHEAD_BYTES = 64 * 1024L
        const val MAX_REQUEST_BODY_BYTES = 24L * 1024 * 1024
    }
}
