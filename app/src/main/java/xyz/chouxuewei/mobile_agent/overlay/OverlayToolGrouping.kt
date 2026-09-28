package xyz.chouxuewei.mobile_agent.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus
import xyz.chouxuewei.mobile_agent.core.localizedText

private const val MAX_STEP_SUMMARY_LENGTH = 80
private val SUMMARY_WHITESPACE = Regex("\\s+")

internal data class OverlayMessageStep(
    val id: String,
    val title: String,
    val detail: String? = null,
    val replyText: String? = null,
    val status: ToolCallStatus? = null,
)

/** Reads only the dedicated model-written label, never raw action parameters or user input. */
internal fun modelStepSummary(argumentsJson: String): String? = runCatching {
    Json.parseToJsonElement(argumentsJson).jsonObject["step_summary"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.replace(SUMMARY_WHITESPACE, " ")
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_STEP_SUMMARY_LENGTH)
}.getOrNull()

/** Rebuilds the exact assistant/tool order into rows consumed by the floating-window stepper. */
internal fun buildOverlayMessageSteps(
    message: Message,
    calls: List<ToolCallRecord>,
    toolTitles: Map<String, String>,
): List<OverlayMessageStep> = buildList {
    val visibleCalls = calls.filterNot { it.status == ToolCallStatus.WAITING_APPROVAL }
    val callsById = visibleCalls.associateBy(ToolCallRecord::id)
    val assignedCallIds = mutableSetOf<String>()

    fun addCall(call: ToolCallRecord) {
        assignedCallIds += call.id
        val toolTitle = toolTitles[call.toolId] ?: localizedText("工具调用", "Tool call")
        val summary = modelStepSummary(call.argumentsJson)
        add(OverlayMessageStep(
            id = "tool:${call.id}",
            title = summary ?: toolTitle,
            detail = call.displaySummary ?: call.error ?: if (summary == null) {
                toolStatusText(call.status)
            } else {
                "$toolTitle · ${toolStatusText(call.status)}"
            },
            status = call.status,
        ))
    }

    fun addReply(text: String, index: Int) {
        text.takeIf(String::isNotBlank)?.let {
            add(OverlayMessageStep(
                id = "reply:${message.id}:$index",
                title = localizedText("处理结果", "Result"),
                replyText = it,
            ))
        }
    }

    if (message.assistantSteps.isEmpty()) {
        visibleCalls.forEach(::addCall)
        addReply(message.text, 0)
    } else {
        message.assistantSteps.forEachIndexed { index, assistantStep ->
            assistantStep.toolCallIds.mapNotNull(callsById::get).forEach(::addCall)
            addReply(assistantStep.text, index)
        }
        // 工具记录可能比消息快照先到一帧；先放在末尾，下一次快照会自动归位。
        visibleCalls.filterNot { it.id in assignedCallIds }.forEach(::addCall)
    }
}

internal fun toolStatusText(status: ToolCallStatus): String = when (status) {
    ToolCallStatus.RECEIVED -> localizedText("准备中", "Preparing")
    ToolCallStatus.WAITING_APPROVAL -> localizedText("等待确认", "Waiting for approval")
    ToolCallStatus.EXECUTING -> localizedText("执行中", "Running")
    ToolCallStatus.SUCCEEDED -> localizedText("已完成", "Completed")
    ToolCallStatus.FAILED -> localizedText("未完成", "Not completed")
    ToolCallStatus.DENIED -> localizedText("未授权", "Not authorized")
    ToolCallStatus.CANCELLED -> localizedText("已停止", "Stopped")
    ToolCallStatus.INTERRUPTED -> localizedText("已中断", "Interrupted")
}
