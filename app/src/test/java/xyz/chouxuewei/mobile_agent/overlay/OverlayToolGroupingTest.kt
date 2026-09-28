package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.AssistantStep
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.MessageRole
import xyz.chouxuewei.mobile_agent.core.MessageStatus
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.ToolCallStatus

class OverlayToolGroupingTest {
    @Test
    fun `assistant rounds become ordered tool and reply steps`() {
        val message = Message(
            id = "assistant",
            conversationId = "conversation",
            sequence = 1L,
            role = MessageRole.ASSISTANT,
            text = "最终结果",
            status = MessageStatus.COMPLETE,
            createdAt = 1L,
            assistantSteps = listOf(
                AssistantStep(toolCallIds = listOf("first")),
                AssistantStep(toolCallIds = listOf("second")),
                AssistantStep(text = "最终结果"),
            ),
        )
        val steps = buildOverlayMessageSteps(
            message = message,
            calls = listOf(
                call("second", ToolCallStatus.SUCCEEDED, "第二步"),
                call("first", ToolCallStatus.SUCCEEDED, "第一步"),
                call("approval", ToolCallStatus.WAITING_APPROVAL, "等待授权"),
            ),
            toolTitles = mapOf("tool-first" to "手机操作", "tool-second" to "手机操作"),
        )

        assertEquals(listOf("tool:first", "tool:second", "reply:assistant:2"), steps.map { it.id })
        assertEquals(listOf("第一步", "第二步", "处理结果"), steps.map { it.title })
        assertEquals("最终结果", steps.last().replyText)
    }

    @Test
    fun `model step summary is normalized and bounded for the compact overlay`() {
        val longSummary = " 正在查找\n目标页面  " + "。".repeat(100)

        val summary = modelStepSummary("""{"step_summary":${jsonString(longSummary)}}""")

        assertEquals(80, summary?.length)
        assertEquals("正在查找 目标页面", summary?.take(9))
    }

    @Test
    fun `missing or malformed model step summary uses the caller fallback`() {
        assertNull(modelStepSummary("{}"))
        assertNull(modelStepSummary("not-json"))
        assertNull(modelStepSummary("""{"step_summary":"   "}"""))
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun call(id: String, status: ToolCallStatus, summary: String = "") = ToolCallRecord(
        id = id,
        conversationId = "conversation",
        runId = "run",
        replyMessageId = "reply",
        toolId = "tool-$id",
        argumentsJson = if (summary.isBlank()) "{}" else """{"step_summary":${jsonString(summary)}}""",
        status = status,
        createdAt = 1L,
    )
}
