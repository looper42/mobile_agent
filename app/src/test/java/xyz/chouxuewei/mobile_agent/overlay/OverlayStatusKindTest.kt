package xyz.chouxuewei.mobile_agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.AssistantStep
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.MessageRole
import xyz.chouxuewei.mobile_agent.core.MessageStatus
import xyz.chouxuewei.mobile_agent.voice.VoiceInputSource
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState
import xyz.chouxuewei.mobile_agent.voice.VoiceInputTarget

class OverlayStatusKindTest {
    @Test
    fun `edge handle distinguishes idle running and completed`() {
        assertEquals(OverlayStatusKind.IDLE, overlayStatusKind(OverlayViewState(), global = true))
        assertEquals(
            OverlayStatusKind.RUNNING,
            overlayStatusKind(OverlayViewState(activeConversations = setOf("running")), global = true),
        )
        assertEquals(
            OverlayStatusKind.COMPLETED,
            overlayStatusKind(OverlayViewState(completionConversationId = "completed"), global = true),
        )
    }

    @Test
    fun `running takes priority over an earlier completion`() {
        val state = OverlayViewState(
            activeConversations = setOf("running"),
            completionConversationId = "completed",
        )

        assertEquals(OverlayStatusKind.RUNNING, overlayStatusKind(state, global = true))
    }

    @Test
    fun `transcribing takes priority over task status`() {
        val target = VoiceInputTarget("voice", null, null, VoiceInputSource.OVERLAY)
        val state = OverlayViewState(
            voiceInputState = VoiceInputState.Transcribing(target),
            activeConversations = setOf("running"),
            completionConversationId = "completed",
        )

        assertEquals(OverlayStatusKind.TRANSCRIBING, overlayStatusKind(state, global = true))
    }

    @Test
    fun `reasoning indicator only represents the currently active reasoning step`() {
        val reasoning = assistantMessage(
            AssistantStep(reasoning = "internal reasoning", reasoningDurationMillis = 2_500L),
        )
        assertEquals(
            OverlayReasoningIndicatorState(2_500L),
            overlayReasoningIndicatorState(OverlayViewState(messages = listOf(reasoning))),
        )

        val executingTool = assistantMessage(
            AssistantStep(reasoning = "internal reasoning", toolCallIds = listOf("call"), reasoningDurationMillis = 2_500L),
        )
        assertNull(overlayReasoningIndicatorState(OverlayViewState(messages = listOf(executingTool))))
    }

    private fun assistantMessage(step: AssistantStep) = Message(
        id = "assistant",
        conversationId = "conversation",
        sequence = 1L,
        role = MessageRole.ASSISTANT,
        text = step.text,
        status = MessageStatus.GENERATING,
        createdAt = 1L,
        assistantSteps = listOf(step),
    )
}
