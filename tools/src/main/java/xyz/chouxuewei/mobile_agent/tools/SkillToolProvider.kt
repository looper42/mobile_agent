package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.chouxuewei.mobile_agent.core.RequestedToolCall
import xyz.chouxuewei.mobile_agent.core.SkillCreator
import xyz.chouxuewei.mobile_agent.core.SkillDraft
import xyz.chouxuewei.mobile_agent.core.SkillLimits
import xyz.chouxuewei.mobile_agent.core.SkillSource
import xyz.chouxuewei.mobile_agent.core.ToolDefinition
import xyz.chouxuewei.mobile_agent.core.ToolExecutionContext
import xyz.chouxuewei.mobile_agent.core.ToolProvider
import xyz.chouxuewei.mobile_agent.core.ToolResult
import xyz.chouxuewei.mobile_agent.core.ToolSideEffect
import xyz.chouxuewei.mobile_agent.core.localizedText

/** Creates persistent user Skills without granting the model access to update, bind, or delete them. */
class SkillToolProvider(
    private val creator: SkillCreator,
) : ToolProvider {
    override val id = "skills"
    override val title get() = localizedText("技能管理", "Skill management")
    override val description get() = localizedText(
        "创建可在设置中心管理、并可通过 / 引用的可复用技能。",
        "Create reusable Skills that can be managed in Settings and referenced with /.",
    )

    override val definitions get() = listOf(
        ToolDefinition(
            id = "skill_create",
            title = localizedText("创建技能", "Create Skill"),
            description = localizedText(
                "仅当用户已经看过最终技能草稿，并在当前对话中明确同意创建时调用。由你根据完整对话理解用户意图，不得要求或匹配固定确认口令，也不得假设确认必须发生在紧邻的上一轮。首次收到创建请求、信息不足、用户要求修改、拒绝或尚未明确同意时，绝不能调用本工具或 ask_user；应采用合理默认值，在普通助手正文中展示完整草稿并结束回复。草稿应清楚列出命令名、显示名称、用途描述和完整指令。用户提出修改后，展示完整修订稿并再次结束回复；经过任意轮沟通后，只有用户明确同意当前最终版本，才可调用本工具，工具参数必须与用户确认的最终版本一致。display_name 和 description 必须各占一行。创建后会长期保存并立即启用，但不会自动用于当前请求、绑定当前会话或设为新会话默认。slash_name 不包含 /，只能使用小写字母、数字和连字符。同名技能不得覆盖。",
                "Call only after the user has seen the final Skill draft and clearly agreed to create it in the current conversation. Infer intent from the full conversation; never require or match a fixed confirmation phrase, and do not assume confirmation must occur in the immediately preceding turn. On the initial creation request, when information is missing, when the user requests changes, refuses, or has not clearly agreed, never call this tool or ask_user. Use reasonable defaults, present the complete draft in the ordinary assistant response, and end the response. Clearly show the slash name, display name, purpose, and complete instructions. After requested changes, show the complete revised draft and end the response again. After any number of discussion turns, call this tool only when the user clearly agrees to the current final version, with arguments matching that confirmed version. display_name and description must each occupy one line. The Skill is persisted and enabled immediately, but is not automatically applied to the current request, bound to the current conversation, or made a default for new conversations. slash_name excludes / and may contain only lowercase letters, numbers, and hyphens. Never overwrite an existing name.",
            ),
            inputSchema = localizedJsonSchema(
                """{"type":"object","properties":{"slash_name":{"type":"string","minLength":1,"maxLength":${SkillLimits.MAX_SLASH_NAME_CHARS},"pattern":"^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$","description":localizedText("不含 / 的命令名，只能使用小写字母、数字和连字符", "Command name without /; use lowercase letters, numbers, and hyphens only")},"display_name":{"type":"string","minLength":1,"maxLength":${SkillLimits.MAX_DISPLAY_NAME_CHARS},"description":localizedText("在设置和聊天中显示的技能名称", "Skill name shown in Settings and chat")},"description":{"type":"string","minLength":1,"maxLength":${SkillLimits.MAX_DESCRIPTION_CHARS},"description":localizedText("简要说明技能的用途和适用场景", "Briefly describe the Skill's purpose and when to use it")},"instructions":{"type":"string","minLength":1,"maxLength":${SkillLimits.MAX_INSTRUCTION_CHARS},"description":localizedText("模型使用该技能时必须遵循的完整指令", "Complete instructions the model must follow when this Skill is used")}},"required":["slash_name","display_name","description","instructions"],"additionalProperties":false}""",
            ),
            sideEffect = ToolSideEffect.LOCAL_WRITE,
            providerId = id,
            requiresPermissionApproval = false,
        ),
    )

    override suspend fun execute(
        call: RequestedToolCall,
        context: ToolExecutionContext,
    ): ToolResult = toolResult {
        require(call.toolId == "skill_create") {
            localizedText("技能工具不支持 ${call.toolId}", "Skill tools do not support ${call.toolId}")
        }
        val args = call.arguments()
        val draft = SkillDraft(
            slashName = required(args, "slash_name").trim(),
            displayName = required(args, "display_name").trim(),
            description = required(args, "description").trim(),
            instructions = required(args, "instructions").trim(),
        )
        require('\n' !in draft.displayName && '\r' !in draft.displayName &&
            '\n' !in draft.description && '\r' !in draft.description) {
            localizedText(
                "技能名称和用途描述必须各占一行",
                "The Skill display name and purpose must each occupy one line.",
            )
        }
        val saved = creator.create(draft, SkillSource.CREATED)
        ToolResult(
            content = buildJsonObject {
                put("created", true)
                put("skill_id", saved.id)
                put("version_id", saved.activeVersionId)
                put("version", saved.version)
                put("slash_name", saved.slashName)
                put("display_name", saved.displayName)
                put("enabled", saved.enabled)
                put("applies_to_current_run", false)
            }.toString(),
            summary = localizedText(
                "已创建技能 /${saved.slashName}（v${saved.version}）",
                "Created Skill /${saved.slashName} (v${saved.version})",
            ),
        )
    }

    private fun required(args: kotlinx.serialization.json.JsonObject, name: String): String =
        args[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error(localizedText("缺少参数 $name", "Missing parameter: $name"))
}
