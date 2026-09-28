package xyz.chouxuewei.mobile_agent.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import xyz.chouxuewei.mobile_agent.attachments.IncomingShare
import xyz.chouxuewei.mobile_agent.core.Artifact
import xyz.chouxuewei.mobile_agent.core.ContextUsage
import xyz.chouxuewei.mobile_agent.core.Conversation
import xyz.chouxuewei.mobile_agent.core.DEFAULT_SINGLE_RUN_MAX_STEPS
import xyz.chouxuewei.mobile_agent.core.ExecutionMode
import xyz.chouxuewei.mobile_agent.core.Message
import xyz.chouxuewei.mobile_agent.core.ThemePreference
import xyz.chouxuewei.mobile_agent.core.ToolAccess
import xyz.chouxuewei.mobile_agent.core.ToolApprovalRequest
import xyz.chouxuewei.mobile_agent.core.ToolCallRecord
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest
import xyz.chouxuewei.mobile_agent.core.withStreamingReply
import xyz.chouxuewei.mobile_agent.core.SkillRef
import xyz.chouxuewei.mobile_agent.core.SkillSummary
import xyz.chouxuewei.mobile_agent.core.SkillUsagePreferences
import xyz.chouxuewei.mobile_agent.data.ModelSettings
import xyz.chouxuewei.mobile_agent.data.ModelUsageSummary
import xyz.chouxuewei.mobile_agent.data.SpeechSettings
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.voice.VoiceInputState

data class ChatSessionUiState(
    val currentConversationId: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val drafts: Map<String, ComposerDraft> = emptyMap(),
    val activeConversationIds: Set<String> = emptySet(),
    val notices: Map<String, String> = emptyMap(),
    val contextUsages: Map<String, ContextUsage> = emptyMap(),
    val approvals: Map<String, ToolApprovalRequest> = emptyMap(),
    val questions: Map<String, UserQuestionRequest> = emptyMap(),
    val incomingShares: List<IncomingShare> = emptyList(),
    val error: String? = null,
    val conversationSkills: List<SkillRef> = emptyList(),
)

data class ChatEnvironmentUiState(
    val themePreference: ThemePreference? = null,
    val persistentOverlay: Boolean? = null,
    val attachmentError: String? = null,
    val deviceMode: ExecutionMode? = null,
    val toolAccesses: Map<String, ToolAccess> = emptyMap(),
    val modelSettings: ModelSettings = ModelSettings(),
    val speechSettings: SpeechSettings = SpeechSettings(),
    val voiceInputState: VoiceInputState = VoiceInputState.Idle,
    val requestedSettingsPage: String? = null,
    val skills: List<SkillSummary> = emptyList(),
    val skillPreferences: SkillUsagePreferences = SkillUsagePreferences(),
)

data class ConversationTimelineState(
    val messages: List<Message> = emptyList(),
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val callsByReply: Map<String, List<ToolCallRecord>> = emptyMap(),
    val artifactsByReply: Map<String, List<Artifact>> = emptyMap(),
)

data class ChatSettingsUiState(
    val activeConversationIds: Set<String> = emptySet(),
    val detailedLogging: Boolean = false,
    val persistentOverlay: Boolean = false,
    val maxSteps: Int = DEFAULT_SINGLE_RUN_MAX_STEPS,
    val personalizedInstructions: String = "",
    val modelUsage: List<ModelUsageSummary> = emptyList(),
    val speechSettings: SpeechSettings = SpeechSettings(),
)

/**
 * Owns the page-level joins so Compose observes three lifecycle-aware states instead of every
 * repository independently. The timeline indexes turn repeated O(messages x calls/artifacts)
 * filtering into one O(n) grouping pass per database emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPresenter(app: PrototypeApplication, scope: CoroutineScope) {
    private val workspace = app.chatWorkspace
    private val started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)

    val session = combine(
        workspace.current,
        app.conversations.observeConversations(),
        workspace.drafts,
        app.chatRuntime.active,
        app.chatRuntime.notices,
    ) { current, conversations, drafts, active, notices ->
        ChatSessionUiState(current, conversations, drafts, active, notices)
    }.merge(app.chatRuntime.contextUsage) { state, value -> state.copy(contextUsages = value) }
        .merge(app.chatRuntime.approvals) { state, value -> state.copy(approvals = value) }
        .merge(app.userQuestions.requests) { state, value -> state.copy(questions = value) }
        .merge(app.attachments.incoming) { state, value -> state.copy(incomingShares = value) }
        .merge(workspace.error) { state, value -> state.copy(error = value) }
        .merge(workspace.current.flatMapLatest { conversationId ->
            if (conversationId == null) flowOf(emptyList()) else app.skills.observeConversationSkills(conversationId)
        }) { state, value -> state.copy(conversationSkills = value) }
        .stateIn(scope, started, ChatSessionUiState())

    val environment = combine(
        app.appearance.theme,
        app.appearance.persistentOverlay,
        app.attachments.error,
        app.deviceGateway.activeMode,
        app.toolPermissions.accesses,
    ) { theme, overlay, attachmentError, deviceMode, toolAccesses ->
        ChatEnvironmentUiState(
            themePreference = theme,
            persistentOverlay = overlay,
            attachmentError = attachmentError,
            deviceMode = deviceMode,
            toolAccesses = toolAccesses,
        )
    }.merge(app.modelSettings.settings) { state, value -> state.copy(modelSettings = value) }
        .merge(app.speechSettings.settings) { state, value -> state.copy(speechSettings = value) }
        .merge(app.voiceInput.state) { state, value -> state.copy(voiceInputState = value) }
        .merge(app.requestedSettingsPage) { state, value -> state.copy(requestedSettingsPage = value) }
        .merge(app.skills.summaries) { state, value -> state.copy(skills = value) }
        .merge(app.skills.preferences) { state, value -> state.copy(skillPreferences = value) }
        .stateIn(scope, started, ChatEnvironmentUiState())

    val timeline = workspace.current.flatMapLatest { conversationId ->
        if (conversationId == null) {
            flowOf(ConversationTimelineState())
        } else {
            combine(
                app.conversations.observeMessages(conversationId),
                app.chatRuntime.streamingReplies,
                app.conversations.observeToolCalls(conversationId),
                app.artifacts.observeArtifacts(conversationId),
            ) { persisted, streaming, calls, artifacts ->
                ConversationTimelineState(
                    messages = persisted.withStreamingReply(streaming[conversationId]),
                    toolCalls = calls,
                    artifacts = artifacts,
                    callsByReply = calls.groupBy(ToolCallRecord::replyMessageId),
                    artifactsByReply = artifacts.groupBy(Artifact::replyMessageId),
                )
            }
        }
    }.stateIn(scope, started, ConversationTimelineState())

    val settings = combine(
        app.chatRuntime.active,
        app.appearance.detailedLogging,
        app.appearance.persistentOverlay,
        app.agentExecutionSettings.maxSteps,
        app.personalization.instructions,
    ) { active, detailedLogging, persistentOverlay, maxSteps, personalization ->
        ChatSettingsUiState(
            activeConversationIds = active,
            detailedLogging = detailedLogging,
            persistentOverlay = persistentOverlay,
            maxSteps = maxSteps,
            personalizedInstructions = personalization,
        )
    }.merge(app.modelUsage.usage) { state, value -> state.copy(modelUsage = value) }
        .merge(app.speechSettings.settings) { state, value -> state.copy(speechSettings = value) }
        .stateIn(scope, started, ChatSettingsUiState())
}

private fun <T, V> Flow<T>.merge(other: Flow<V>, transform: (T, V) -> T): Flow<T> =
    combine(this, other, transform)
