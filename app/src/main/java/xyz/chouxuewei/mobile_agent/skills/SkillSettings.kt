package xyz.chouxuewei.mobile_agent.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.provider.OpenableColumns
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.chouxuewei.mobile_agent.core.SkillActivationScope
import xyz.chouxuewei.mobile_agent.core.SkillDraft
import xyz.chouxuewei.mobile_agent.core.SkillSource
import xyz.chouxuewei.mobile_agent.core.SkillSummary
import xyz.chouxuewei.mobile_agent.core.localizedText
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.data.SkillPackageParser
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.ui.theme.LocalChatColors

@Composable
fun SkillSettingsPage(app: PrototypeApplication) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills by app.skills.summaries.collectAsStateWithLifecycle(emptyList())
    val preferences by app.skills.preferences.collectAsStateWithLifecycle(
        xyz.chouxuewei.mobile_agent.core.SkillUsagePreferences(),
    )
    var editor by remember { mutableStateOf<SkillEditorState?>(null) }
    var deleting by remember { mutableStateOf<SkillSummary?>(null) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun change(block: suspend () -> Unit) {
        scope.launch {
            notice = null
            runCatching { block() }.onFailure { failure ->
                notice = userFacingMessage(failure, localizedText("设置未保存", "The setting was not saved.")) to false
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            notice = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "SKILL.md"
                    val input = requireNotNull(context.contentResolver.openInputStream(uri))
                    input.use { SkillPackageParser.parse(name, it) }
                }
            }.onSuccess { draft ->
                editor = SkillEditorState(null, draft, SkillSource.IMPORTED)
            }.onFailure { failure ->
                notice = userFacingMessage(failure, localizedText("技能导入失败", "Skill import failed.")) to false
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(localizedText("技能", "Skills"), style = MaterialTheme.typography.headlineSmall)
        Text(
            localizedText(
                "创建或导入可复用工作流，在聊天输入 / 即可选择相关工作流技能。",
                "Create or import reusable workflows. Type / in chat to select one. ",
            ),
            color = colors.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        notice?.let { (message, success) ->
            Surface(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = if (success) colors.successSoft else colors.errorSoft,
            ) {
                Text(message, Modifier.padding(14.dp), color = if (success) colors.success else colors.error)
            }
        }

        Surface(
            Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(localizedText("引用后的默认作用域", "Default activation scope"), fontWeight = FontWeight.SemiBold)
                ScopeChoice(
                    selected = preferences.defaultScope,
                    enabled = !busy,
                    onSelect = { selected -> change { app.skills.setDefaultScope(selected) } },
                )
                Text(
                    localizedText(
                        "“每次询问”会在选择技能后询问用于本轮还是固定到当前会话。",
                        "Ask every time prompts whether to use a Skill once or pin it to the conversation.",
                    ),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { editor = SkillEditorState(null, SkillDraft("", "", "", ""), SkillSource.CREATED) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) { Text(localizedText("新建技能", "New Skill")) }
            OutlinedButton(
                onClick = { importer.launch(arrayOf("text/*", "application/zip", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            ) { Text(if (busy) localizedText("正在读取…", "Reading…") else localizedText("导入", "Import")) }
        }

        if (skills.isEmpty()) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = colors.surfaceRaised,
            ) {
                Text(
                    localizedText("您还没有添加技能。请新建一个，或导入包含 SKILL.md 的文件或 ZIP。", "No Skills yet. Create one or import a SKILL.md file or ZIP."),
                    Modifier.padding(18.dp),
                    color = colors.secondary,
                )
            }
        } else {
            Text(localizedText("已安装", "Installed"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            skills.forEach { skill ->
                SkillSettingsCard(
                    skill = skill,
                    isDefault = skill.id in preferences.defaultSkillIds,
                    onEnabled = { enabled -> change { app.skills.setEnabled(skill.id, enabled) } },
                    onDefault = { enabled -> change { app.skills.setDefaultSkill(skill.id, enabled) } },
                    onEdit = {
                        scope.launch {
                            runCatching { requireNotNull(app.skills.skillDraft(skill.id)) }
                                .onSuccess { draft -> editor = SkillEditorState(skill.id, draft, skill.source) }
                                .onFailure { notice = userFacingMessage(it) to false }
                        }
                    },
                    onDelete = { deleting = skill },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editor?.let { state ->
        SkillEditorDialog(
            state = state,
            saving = busy,
            onDismiss = { if (!busy) editor = null },
            onSave = { draft ->
                scope.launch {
                    busy = true
                    notice = null
                    runCatching {
                        if (state.skillId == null) app.skills.create(draft, state.source)
                        else app.skills.update(state.skillId, draft)
                    }.onSuccess { saved ->
                        notice = localizedText("技能 /${saved.slashName} 已保存", "Skill /${saved.slashName} saved.") to true
                        editor = null
                    }.onFailure { failure ->
                        notice = userFacingMessage(failure, localizedText("技能未保存", "Skill was not saved.")) to false
                    }
                    busy = false
                }
            },
        )
    }

    deleting?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(localizedText("删除技能？", "Delete Skill?")) },
            text = { Text(localizedText("历史消息仍保留已使用的版本；新消息将不能再选择 /${skill.slashName}。", "Historical messages retain the version they used. New messages can no longer select /${skill.slashName}.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { app.skills.delete(skill.id) }
                            .onFailure { notice = userFacingMessage(it) to false }
                    }
                }) { Text(localizedText("删除", "Delete"), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(localizedText("取消", "Cancel")) } },
        )
    }
}

private data class SkillEditorState(val skillId: String?, val draft: SkillDraft, val source: SkillSource)

@Composable
private fun ScopeChoice(selected: SkillActivationScope, enabled: Boolean, onSelect: (SkillActivationScope) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            SkillActivationScope.ONCE to localizedText("仅本轮生效", "This turn only"),
            SkillActivationScope.CONVERSATION to localizedText("在当前会话持续生效", "Keep active in this conversation"),
            SkillActivationScope.ASK to localizedText("每次选择时询问", "Ask every time"),
        ).forEach { (scope, label) ->
            Surface(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(scope) },
                color = if (selected == scope) LocalChatColors.current.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Text(
                    (if (selected == scope) "✓  " else "   ") + label,
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = if (selected == scope) LocalChatColors.current.accent else LocalChatColors.current.text,
                )
            }
        }
    }
}

@Composable
private fun SkillSettingsCard(
    skill: SkillSummary,
    isDefault: Boolean,
    onEnabled: (Boolean) -> Unit,
    onDefault: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("/${skill.slashName} · v${skill.version}", color = colors.accent, style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = skill.enabled, onCheckedChange = onEnabled)
            }
            Text(skill.description, color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localizedText("新会话默认启用", "Enable in new conversations"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = isDefault, onCheckedChange = onDefault, enabled = skill.enabled)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text(localizedText("编辑", "Edit")) }
                TextButton(onClick = onDelete) { Text(localizedText("删除", "Delete"), color = colors.error) }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(
    state: SkillEditorState,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (SkillDraft) -> Unit,
) {
    var slashName by remember(state) { mutableStateOf(state.draft.slashName) }
    var displayName by remember(state) { mutableStateOf(state.draft.displayName) }
    var description by remember(state) { mutableStateOf(state.draft.description) }
    var instructions by remember(state) { mutableStateOf(state.draft.instructions) }
    val canSave = slashName.isNotBlank() && displayName.isNotBlank() && description.isNotBlank() && instructions.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.skillId == null) localizedText("新建技能", "New Skill") else localizedText("编辑技能", "Edit Skill")) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(displayName, { displayName = it.take(100) }, Modifier.fillMaxWidth(), label = { Text(localizedText("名称", "Name")) }, singleLine = true)
                OutlinedTextField(slashName, { slashName = it.trimStart('/').take(64) }, Modifier.fillMaxWidth(), label = { Text(localizedText("/命令名", "/command")) }, singleLine = true)
                OutlinedTextField(description, { description = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(localizedText("用途和触发条件", "Purpose and when to use")) }, minLines = 2, maxLines = 4)
                OutlinedTextField(instructions, { instructions = it.take(24_000) }, Modifier.fillMaxWidth(), label = { Text(localizedText("工作流指令（Markdown）", "Workflow instructions (Markdown)")) }, minLines = 8, maxLines = 14)
                if (state.source == SkillSource.IMPORTED) {
                    Text(localizedText("导入内容按用户级指令处理，不会执行包内脚本。", "Imported content is treated as user-level instructions. Bundled scripts are not executed."), color = LocalChatColors.current.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(SkillDraft(slashName, displayName, description, instructions)) },
                enabled = canSave && !saving,
            ) { Text(if (saving) localizedText("保存中…", "Saving…") else localizedText("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(localizedText("取消", "Cancel")) } },
    )
}
