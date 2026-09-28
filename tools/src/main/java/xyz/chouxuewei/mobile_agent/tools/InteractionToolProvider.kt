package xyz.chouxuewei.mobile_agent.tools

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.ToolAvailability
import xyz.chouxuewei.mobile_agent.core.ToolAvailabilityState
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import xyz.chouxuewei.mobile_agent.core.UserQuestionBroker
import xyz.chouxuewei.mobile_agent.core.UserQuestionRequest

/** 业务澄清入口；工具授权仍由 ChatRuntime 的审批流程独立负责。 */
class InteractionToolProvider(private val questions: UserQuestionBroker) : ToolProvider {
    override val id = "interaction"
    override val title get() = localizedText("询问用户", "Ask user")
    override val description get() = localizedText("仅供正在执行的后台或设备任务暂停并等待一个结构化回答。普通聊天应直接回复并结束当前轮次。", "Only pause an active background or device task to wait for one structured answer. Ordinary chat must ask in the response and end the current turn.")
    override val definitions get() = listOf(
        ToolDefinition(
            id = "ask_user",
            title = localizedText("询问用户", "Ask user"),
            description = localizedText("仅当后台或设备任务已经开始执行，并且必须保留当前运行状态等待一个结构化回答时调用；每个运行最多一次。普通聊天澄清、技能创建、草稿修改和内容确认必须直接写在助手正文中，然后结束当前回复，绝不能调用本工具。可提供互斥选项并允许自由输入。不要用于工具授权，也不要索要密码、验证码、支付信息、API 密钥或账号安全凭据。", "Call only after a background or device task has started and must preserve the current run while waiting for one structured answer; at most once per run. Never call this tool for ordinary chat clarification, Skill creation, draft revision, or content confirmation: put those questions directly in the assistant response and end the current response. You may offer mutually exclusive choices and free-form input. Do not use it for tool approval or ask for passwords, verification codes, payment information, API keys, or account security credentials."),
            inputSchema = localizedJsonSchema("""{"type":"object","properties":{"question":{"type":"string","minLength":1,"maxLength":500,"description":localizedText("需要用户回答的一个明确问题", "One clear question for the user")},"options":{"type":"array","items":{"type":"string","minLength":1,"maxLength":100},"maxItems":6,"uniqueItems":true,"description":localizedText("可选的互斥答案；不确定时可省略", "Optional mutually exclusive answers; omit when uncertain")},"allow_free_text":{"type":"boolean","default":true,"description":localizedText("是否允许用户输入自定义答案", "Whether the user may enter a custom answer")}},"required":["question"],"additionalProperties":false}"""),
            sideEffect = ToolSideEffect.READ,
            providerId = id,
            approvalDescription = localizedText("等待用户补充关键信息。", "Wait for the user to provide key information."),
            requiresPermissionApproval = false,
        ),
    )

    override suspend fun availability() = ToolAvailability(ToolAvailabilityState.AVAILABLE)

    override suspend fun execute(call: RequestedToolCall, context: ToolExecutionContext): ToolResult = toolResult {
        require(call.toolId == "ask_user") { localizedText("询问工具不支持 ${call.toolId}", "Question tools do not support ${call.toolId}") }
        val args = call.arguments()
        val question = args["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val options = args["options"]?.jsonArray
            ?.map { it.jsonPrimitive.content.trim() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val allowFreeText = args["allow_free_text"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: true
        val requestId = requireNotNull(context.toolCallRecordId) { localizedText("询问工具缺少调用记录", "The question tool call record is missing.") }
        val answer = questions.ask(UserQuestionRequest(
            id = requestId,
            conversationId = context.conversationId,
            question = question,
            options = options,
            allowFreeText = allowFreeText,
        ))
        ToolResult(
            content = buildJsonObject {
                put("answered", answer.answered)
                answer.value?.let { put("answer", it) }
                putJsonArray("offered_options") { options.forEach { add(JsonPrimitive(it)) } }
            }.toString(),
            summary = if (answer.answered) localizedText("用户已回答", "User answered") else localizedText("用户暂未回答", "User has not answered yet"),
        )
    }
}
