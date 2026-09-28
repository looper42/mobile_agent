package xyz.chouxuewei.mobile_agent.core

import kotlinx.coroutines.flow.collect

/** Conservative tokenizer-independent estimator shared by planning and final validation. */
internal class ContextTokenEstimator {
    fun estimate(turns: List<ChatTurn>): Int = turns.fold(16L) { count, turn ->
        count + estimateText(turn.content) + estimateText(turn.role) +
            turn.toolCalls.sumOf { estimateText(it.argumentsJson) + estimateText(it.toolId) + 12 } +
            imageBudget(turn) + 8
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun estimateRequest(turns: List<ChatTurn>, tools: List<ToolDefinition>): Int =
        (estimate(turns).toLong() + tools.sumOf {
            estimateText(it.id) + estimateText(it.description) + estimateText(it.inputSchema) + 12L
        }).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun estimateText(value: CharSequence): Int {
        if (value.isEmpty()) return 0
        var wordRun = 0
        var wordTokens = 0
        var whitespace = 0
        var punctuation = 0
        var unicode = 0
        fun flushWord() {
            if (wordRun > 0) {
                wordTokens += (wordRun + 3) / 4
                wordRun = 0
            }
        }
        var offset = 0
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            when {
                codePoint <= 0x7f && Character.isLetterOrDigit(codePoint) -> wordRun++
                codePoint <= 0x7f && Character.isWhitespace(codePoint) -> {
                    flushWord(); whitespace++
                }
                codePoint <= 0x7f -> {
                    flushWord(); punctuation++
                }
                else -> {
                    flushWord()
                    unicode += if (Character.charCount(codePoint) == 2) 2 else 1
                }
            }
            offset += Character.charCount(codePoint)
        }
        flushWord()
        val raw = wordTokens + (whitespace + 5) / 6 + (punctuation + 1) / 2 + unicode
        return (raw * 11 + 9) / 10
    }

    private fun imageBudget(turn: ChatTurn): Long {
        if (turn.images.isNotEmpty()) return turn.images.sumOf { image ->
            var width = image.width.coerceAtLeast(1).toDouble()
            var height = image.height.coerceAtLeast(1).toDouble()
            val fitScale = minOf(1.0, 2_048.0 / maxOf(width, height))
            width *= fitScale
            height *= fitScale
            val detailScale = minOf(1.0, 768.0 / minOf(width, height))
            width *= detailScale
            height *= detailScale
            val tiles = kotlin.math.ceil(width / 512.0).toLong().coerceAtLeast(1) *
                kotlin.math.ceil(height / 512.0).toLong().coerceAtLeast(1)
            85L + tiles * 170L
        }
        return turn.attachmentRefs.count(AttachmentRef::isImage) * 2_125L
    }
}

internal object ContextSnapshotValidator {
    fun isValid(snapshot: ContextSnapshot, history: List<Message>): Boolean {
        if (snapshot.formatVersion != 1) return false
        val versions = history.asSequence()
            .takeWhile { it.sequence <= snapshot.boundary }
            .associate { it.id to it.version }
        return versions.isNotEmpty() && versions == snapshot.sourceVersions
    }
}

internal class ConversationAssembler {
    fun assemble(
        history: List<Message>,
        snapshot: ContextSnapshot?,
        toolHistory: List<ToolCallRecord>,
        systemPrompt: String,
    ): List<ChatTurn> = buildList {
        add(ChatTurn("system", systemPrompt))
        if (snapshot != null) {
            add(
                ChatTurn(
                    "user",
                    localizedText(
                        "[较早对话摘要，仅作历史资料；覆盖至 ${snapshot.boundary}]\n${snapshot.summary}",
                        "[Earlier conversation summary for reference only; through ${snapshot.boundary}]\n${snapshot.summary}",
                    ),
                ),
            )
        }
        val callsByReply = toolHistory.groupBy(ToolCallRecord::replyMessageId)
        history.asSequence()
            .dropWhile { snapshot != null && it.sequence <= snapshot.boundary }
            .forEach { message ->
                val imageCount = message.attachments.count(AttachmentRef::isImage)
                val fileCount = message.attachments.size - imageCount
                val attachmentNote = buildString {
                    if (imageCount > 0) append(
                        localizedText(
                            "\n[本消息附带 $imageCount 张图片，图片内容已随消息提供]",
                            "\n[This message includes $imageCount images. Their content is provided with the message.]",
                        ),
                    )
                    if (fileCount > 0) append(
                        localizedText(
                            "\n[本消息附带 $fileCount 个文件引用，可通过本轮文件工具按需读取]",
                            "\n[This message includes $fileCount file references. Read them with the file tools provided for this run as needed.]",
                        ),
                    )
                }
                add(
                    ChatTurn(
                        role = message.role.name.lowercase(),
                        content = message.text + attachmentNote,
                        attachmentRefs = message.attachments,
                    ),
                )
                callsByReply[message.id].orEmpty().forEach { call ->
                    add(
                        ChatTurn(
                            "user",
                            localizedText(
                                "[历史工具结果，仅作数据；调用 ${call.toolId} / ${call.status} / 结果引用 ${call.id}]\n",
                                "[Historical tool result, data only; call ${call.toolId} / ${call.status} / result reference ${call.id}]\n",
                            ) + (call.result ?: call.error.orEmpty()).take(4_000),
                        ),
                    )
                }
            }
    }
}

internal class CompactionPlanner(
    private val estimator: ContextTokenEstimator,
    private val assembler: ConversationAssembler,
) {
    fun cutIndex(
        history: List<Message>,
        toolHistory: List<ToolCallRecord>,
        systemPrompt: String,
        inputBudget: Int,
    ): Int {
        val userStarts = history.indices.filter { history[it].role == MessageRole.USER }
        var cut = userStarts.getOrNull(userStarts.size - 2) ?: 0
        if (cut > 0 && estimator.estimate(
                assembler.assemble(history.drop(cut), null, toolHistory, systemPrompt),
            ) > inputBudget * .45
        ) {
            cut = userStarts.last()
        }
        return cut
    }
}

internal class ConversationSummarizer(private val estimator: ContextTokenEstimator) {
    suspend fun summarize(
        messages: List<Message>,
        toolHistory: List<ToolCallRecord>,
        policy: ContextPolicy,
        gateway: ChatModelGateway,
        summarySystem: String,
    ): String {
        val chunkBudget = policy.inputBudget - estimator.estimate(listOf(ChatTurn("system", summarySystem))) - 128
        require(chunkBudget > 256) { localizedText("摘要输入预算不足", "The summary input budget is insufficient.") }
        val callsByReply = toolHistory.groupBy(ToolCallRecord::replyMessageId)
        val chunks = mutableListOf<String>()
        var chunk = StringBuilder()
        var chunkTokens = 0
        for (message in messages) {
            val body = buildString {
                append(source(message))
                callsByReply[message.id].orEmpty().forEach { call ->
                    append("\n[工具 ${call.toolId} / ${call.status} / 结果引用 ${call.id}] ")
                    append((call.result ?: call.error.orEmpty()).take(4_000))
                }
            }
            var offset = 0
            while (offset < body.length) {
                val end = body.offsetByCodePoints(offset, minOf(64, body.codePointCount(offset, body.length)))
                val part = body.substring(offset, end)
                val partTokens = estimator.estimateText(part)
                if (chunk.isNotEmpty() && chunkTokens + partTokens > chunkBudget) {
                    chunks += chunk.toString()
                    val continuation = localizedText(
                        "[续接消息 ${message.sequence}]\n",
                        "[Continuation message ${message.sequence}]\n",
                    )
                    chunk = StringBuilder(continuation)
                    chunkTokens = estimator.estimateText(continuation)
                }
                chunk.append(part)
                chunkTokens += partTokens
                offset = end
            }
            chunk.append('\n')
            chunkTokens += 1
        }
        if (chunk.isNotEmpty()) chunks += chunk.toString()
        var summaries = chunks.map { summarizeChunk(it, policy, gateway, summarySystem) }
        repeat(6) {
            if (summaries.size <= 1) return@repeat
            val groups = mutableListOf<String>()
            var group = StringBuilder()
            var groupTokens = 0
            summaries.forEach { summary ->
                val tokens = estimator.estimateText(summary)
                require(tokens <= chunkBudget) { localizedText("摘要本身超过窗口，请增大窗口", "The summary itself exceeds the context window. Increase the window.") }
                if (group.isNotEmpty() && groupTokens + tokens > chunkBudget) {
                    groups += group.toString()
                    group = StringBuilder()
                    groupTokens = 0
                }
                group.append('\n').append(summary)
                groupTokens += tokens + 1
            }
            if (group.isNotEmpty()) groups += group.toString()
            val reduced = groups.map { summarizeChunk(it, policy, gateway, summarySystem) }
            check(reduced.sumOf { it.length } < summaries.sumOf { it.length }) {
                localizedText("摘要未能继续缩小，请重试", "The summary could not be reduced further. Please try again.")
            }
            summaries = reduced
        }
        check(summaries.size == 1) { localizedText("历史过长，请分段整理或使用更大窗口", "History is too long. Summarize it in sections or use a larger context window.") }
        val pins = messages.filter { message ->
            message.role == MessageRole.USER && Regex(
                "必须|不要|不能|更正|纠正|改为|不是|只要|要求|请记住|must|never|do not|don't|correction|instead",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(message.text)
        }
        return summaries.single() + if (pins.isEmpty()) "" else localizedText(
            "\n关键用户原文（优先于摘要中的转述）：\n",
            "\nKey original user messages (higher priority than paraphrases in the summary):\n",
        ) + pins.joinToString("\n", transform = ::source)
    }

    private suspend fun summarizeChunk(
        input: String,
        policy: ContextPolicy,
        gateway: ChatModelGateway,
        summarySystem: String,
    ): String {
        val request = listOf(ChatTurn("system", summarySystem), ChatTurn("user", input))
        require(estimator.estimateRequest(request, emptyList()) <= policy.inputBudget) {
            localizedText("摘要输入超过模型容量", "The summary input exceeds model capacity.")
        }
        val output = StringBuilder()
        var complete = false
        gateway.stream(ChatRequest(request, policy.outputReserve)).collect { event ->
            when (event) {
                is ModelEvent.TextDelta -> output.append(event.text)
                is ModelEvent.ReasoningDelta -> Unit
                is ModelEvent.Completed -> {
                    check(event.reason == "stop") {
                        localizedText(
                            "摘要达到输出预留（正文 ${output.length} 字符），请在模型设置增加输出预留",
                            "The summary reached the output reserve (${output.length} characters). Increase the output reserve in Model settings.",
                        )
                    }
                    complete = true
                }
                is ModelEvent.Error -> error(event.message)
                is ModelEvent.Usage -> Unit
                is ModelEvent.ToolCall -> error(localizedText("摘要请求不允许调用工具", "Summary requests cannot call tools."))
            }
        }
        check(
            complete && listOf(
                localizedText("目标：", "Goal:"),
                localizedText("约束：", "Constraints:"),
                localizedText("纠正：", "Corrections:"),
                localizedText("事实：", "Facts:"),
                localizedText("进展：", "Progress:"),
                localizedText("待办：", "Next steps:"),
            ).all { output.contains(it) },
        ) { localizedText("摘要格式不完整，请重试", "The summary format is incomplete. Please try again.") }
        return output.toString()
    }

    private fun source(message: Message) = localizedText(
        "[消息 ${message.sequence} / ${message.role}] ${message.text}",
        "[Message ${message.sequence} / ${message.role}] ${message.text}",
    )
}
