package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.chouxuewei.mobile_agent.core.ResolvedSkill
import xyz.chouxuewei.mobile_agent.core.SkillActivationScope
import xyz.chouxuewei.mobile_agent.core.SkillCatalog
import xyz.chouxuewei.mobile_agent.core.SkillDraft
import xyz.chouxuewei.mobile_agent.core.SkillInstructionResolver
import xyz.chouxuewei.mobile_agent.core.SkillLimits
import xyz.chouxuewei.mobile_agent.core.SkillRef
import xyz.chouxuewei.mobile_agent.core.SkillSource
import xyz.chouxuewei.mobile_agent.core.SkillSummary
import xyz.chouxuewei.mobile_agent.core.SkillUsagePreferences
import xyz.chouxuewei.mobile_agent.core.localizedText

private val Context.skillPreferencesDataStore by preferencesDataStore("skill_preferences")

class RoomSkillRepository internal constructor(
    private val context: Context,
    private val database: AgentDatabase,
) : SkillCatalog, SkillInstructionResolver {
    constructor(context: Context) : this(context.applicationContext, DatabaseProvider.get(context))

    private val dao = database.skills()
    private val preferenceStore = context.applicationContext.skillPreferencesDataStore
    private val defaultScopeKey = stringPreferencesKey("default_scope")
    private val defaultSkillsKey = stringSetPreferencesKey("default_skill_ids")

    override val summaries: Flow<List<SkillSummary>> = dao.observeSummaries().map { rows ->
        rows.map(SkillSummaryProjection::record)
    }

    override val preferences: Flow<SkillUsagePreferences> = preferenceStore.data.map { values ->
        SkillUsagePreferences(
            runCatching { SkillActivationScope.valueOf(values[defaultScopeKey].orEmpty()) }
                .getOrDefault(SkillActivationScope.ONCE),
            values[defaultSkillsKey].orEmpty(),
        )
    }.distinctUntilChanged()

    override fun observeConversationSkills(conversationId: String): Flow<List<SkillRef>> =
        dao.observeConversationRefs(conversationId).map { rows -> rows.map(SkillRefProjection::record) }

    override suspend fun conversationSkills(conversationId: String): List<SkillRef> =
        dao.conversationRefs(conversationId).map(SkillRefProjection::record)

    override suspend fun applyDefaults(conversationId: String) {
        val ids = preferenceStore.data.first()[defaultSkillsKey].orEmpty()
        if (ids.isEmpty()) return
        database.withTransaction {
            val now = System.currentTimeMillis()
            dao.activeRefs(ids.toList()).forEach { ref ->
                dao.bind(ConversationSkillEntity(conversationId, ref.skillId, ref.versionId, now))
            }
        }
    }

    override suspend fun bindConversation(conversationId: String, skill: SkillRef) = database.withTransaction {
        require(dao.skill(skill.skillId)?.enabled == true) {
            localizedText("这个技能已停用或不存在", "This Skill is disabled or no longer exists.")
        }
        val version = dao.resolvedVersions(listOf(skill.versionId)).singleOrNull()
        require(version?.skillId == skill.skillId) {
            localizedText("找不到这个技能版本", "This Skill version could not be found.")
        }
        val current = dao.conversationRefs(conversationId)
        require(current.any { it.skillId == skill.skillId } || current.size < SkillLimits.MAX_SELECTED) {
            localizedText(
                "一个会话最多启用 ${SkillLimits.MAX_SELECTED} 个技能",
                "A conversation can enable at most ${SkillLimits.MAX_SELECTED} Skills.",
            )
        }
        dao.bind(ConversationSkillEntity(conversationId, skill.skillId, skill.versionId, System.currentTimeMillis()))
    }

    override suspend fun unbindConversation(conversationId: String, skillId: String) {
        dao.unbind(conversationId, skillId)
    }

    override suspend fun create(draft: SkillDraft, source: SkillSource): SkillSummary = database.withTransaction {
        val clean = validate(draft)
        require(dao.skillBySlashName(clean.slashName) == null) {
            localizedText("/${clean.slashName} 已存在", "/${clean.slashName} already exists.")
        }
        val now = System.currentTimeMillis()
        val skillId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        dao.insert(SkillEntity(
            id = skillId,
            slashName = clean.slashName,
            displayName = clean.displayName,
            description = clean.description,
            activeVersionId = versionId,
            enabled = true,
            source = source.name,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ))
        dao.insert(SkillVersionEntity(
            id = versionId,
            skillId = skillId,
            version = 1,
            description = clean.description,
            instructions = clean.instructions,
            manifestJson = "{}",
            contentHash = contentHash(clean),
            createdAt = now,
        ))
        SkillSummary(skillId, clean.slashName, clean.displayName, clean.description, versionId, 1, true, source, now)
    }

    override suspend fun update(skillId: String, draft: SkillDraft): SkillSummary = database.withTransaction {
        val existing = requireNotNull(dao.skill(skillId)) {
            localizedText("找不到要编辑的技能", "The Skill to edit could not be found.")
        }
        val clean = validate(draft)
        val conflict = dao.skillBySlashName(clean.slashName)
        require(conflict == null || conflict.id == skillId) {
            localizedText("/${clean.slashName} 已存在", "/${clean.slashName} already exists.")
        }
        val nextVersion = (dao.latestVersion(skillId) ?: 0) + 1
        val versionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insert(SkillVersionEntity(
            versionId, skillId, nextVersion, clean.description, clean.instructions, "{}", contentHash(clean), now,
        ))
        check(dao.update(skillId, clean.slashName, clean.displayName, clean.description, versionId, now) == 1)
        SkillSummary(
            skillId, clean.slashName, clean.displayName, clean.description, versionId, nextVersion,
            existing.enabled, SkillSource.valueOf(existing.source), now,
        )
    }

    override suspend fun setEnabled(skillId: String, enabled: Boolean) {
        check(dao.setEnabled(skillId, enabled, System.currentTimeMillis()) == 1) {
            localizedText("找不到要设置的技能", "The Skill to update could not be found.")
        }
        if (!enabled) {
            preferenceStore.edit { values ->
                values[defaultSkillsKey] = values[defaultSkillsKey].orEmpty() - skillId
            }
        }
    }

    override suspend fun delete(skillId: String) {
        check(dao.softDelete(skillId, System.currentTimeMillis()) == 1) {
            localizedText("找不到要删除的技能", "The Skill to delete could not be found.")
        }
        preferenceStore.edit { values ->
            values[defaultSkillsKey] = values[defaultSkillsKey].orEmpty() - skillId
        }
    }

    override suspend fun skillDraft(skillId: String): SkillDraft? = dao.draft(skillId)?.let {
        SkillDraft(it.slashName, it.displayName, it.description, it.instructions)
    }

    override suspend fun setDefaultScope(scope: SkillActivationScope) {
        preferenceStore.edit { it[defaultScopeKey] = scope.name }
    }

    override suspend fun setDefaultSkill(skillId: String, enabled: Boolean) {
        if (enabled) requireNotNull(dao.skill(skillId)) {
            localizedText("找不到要设为默认的技能", "The default Skill could not be found.")
        }
        preferenceStore.edit { values ->
            val current = values[defaultSkillsKey].orEmpty()
            require(!enabled || skillId in current || current.size < SkillLimits.MAX_SELECTED) {
                localizedText("新会话最多默认启用 ${SkillLimits.MAX_SELECTED} 个技能", "New conversations can enable at most ${SkillLimits.MAX_SELECTED} default Skills.")
            }
            values[defaultSkillsKey] = if (enabled) current + skillId else current - skillId
        }
    }

    override suspend fun resolveSkills(refs: List<SkillRef>): List<ResolvedSkill> {
        if (refs.isEmpty()) return emptyList()
        require(refs.size <= SkillLimits.MAX_SELECTED) {
            localizedText("一次最多使用 ${SkillLimits.MAX_SELECTED} 个技能", "Use at most ${SkillLimits.MAX_SELECTED} Skills at a time.")
        }
        val byVersion = dao.resolvedVersions(refs.map(SkillRef::versionId)).associateBy { it.versionId }
        return refs.map { ref ->
            val resolved = requireNotNull(byVersion[ref.versionId]) {
                localizedText("技能 /${ref.slashName} 的版本已不可用", "The version of Skill /${ref.slashName} is unavailable.")
            }
            require(resolved.skillId == ref.skillId) {
                localizedText("技能 /${ref.slashName} 的版本不匹配", "The version of Skill /${ref.slashName} does not match.")
            }
            ResolvedSkill(ref, resolved.description, resolved.instructions)
        }
    }

    private fun validate(draft: SkillDraft): SkillDraft {
        val slashName = draft.slashName.trim().lowercase(Locale.ROOT)
        require(slashName.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?"))) {
            localizedText("命令名只能使用小写字母、数字和连字符，且不能以连字符开头或结尾", "The command name may contain lowercase letters, numbers, and hyphens, and cannot start or end with a hyphen.")
        }
        val displayName = draft.displayName.trim()
        val description = draft.description.trim()
        val instructions = draft.instructions.trim()
        require(displayName.isNotEmpty() && displayName.length <= SkillLimits.MAX_DISPLAY_NAME_CHARS) {
            localizedText("请填写不超过 ${SkillLimits.MAX_DISPLAY_NAME_CHARS} 个字符的名称", "Enter a name up to ${SkillLimits.MAX_DISPLAY_NAME_CHARS} characters.")
        }
        require(description.isNotEmpty() && description.length <= SkillLimits.MAX_DESCRIPTION_CHARS) {
            localizedText("请填写不超过 ${SkillLimits.MAX_DESCRIPTION_CHARS} 个字符的用途描述", "Enter a description up to ${SkillLimits.MAX_DESCRIPTION_CHARS} characters.")
        }
        require(instructions.isNotEmpty() && instructions.length <= SkillLimits.MAX_INSTRUCTION_CHARS) {
            localizedText("技能指令不能为空，且不能超过 ${SkillLimits.MAX_INSTRUCTION_CHARS} 个字符", "Skill instructions are required and cannot exceed ${SkillLimits.MAX_INSTRUCTION_CHARS} characters.")
        }
        return SkillDraft(slashName, displayName, description, instructions)
    }

    private fun contentHash(draft: SkillDraft): String {
        val bytes = "${draft.slashName}\n${draft.description}\n${draft.instructions}".toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private fun SkillSummaryProjection.record() = SkillSummary(
    id, slashName, displayName, description, activeVersionId, version, enabled,
    SkillSource.valueOf(source), updatedAt,
)

private fun SkillRefProjection.record() = SkillRef(skillId, versionId, slashName, displayName)
