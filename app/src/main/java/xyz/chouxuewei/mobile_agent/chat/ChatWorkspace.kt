package xyz.chouxuewei.mobile_agent.chat

import xyz.chouxuewei.mobile_agent.core.localizedText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import xyz.chouxuewei.mobile_agent.core.*
import xyz.chouxuewei.mobile_agent.prototype.PrototypeApplication
import xyz.chouxuewei.mobile_agent.core.userFacingMessage
import xyz.chouxuewei.mobile_agent.attachments.IncomingShare

data class ComposerDraft(
    val text: String = "",
    val attachments: List<AttachmentRef> = emptyList(),
    val skills: List<SkillRef> = emptyList(),
)

/** UI 状态独立于配色和 Activity；有序写入避免慢磁盘把旧草稿覆盖回去。 */
class ChatWorkspace(private val app: PrototypeApplication) {
    val current = MutableStateFlow<String?>(null)
    val drafts = MutableStateFlow<Map<String, ComposerDraft>>(emptyMap())
    val error = MutableStateFlow<String?>(null)
    private val commands = Channel<suspend () -> Unit>(Channel.BUFFERED)
    private val draftPersistence = DraftPersistenceCoordinator(
        scope = app.applicationScope,
        persist = { pending ->
            app.conversations.saveDraft(
                pending.conversationId,
                pending.draft.text,
                pending.draft.attachments,
                null,
                pending.draft.skills,
            )
            app.attachments.release(pending.draft.attachments)
            if (pending.cleanupAttachments) app.attachments.cleanup()
        },
        onFailure = { failure ->
            error.value = userFacingMessage(
                failure,
                localizedText("草稿未保存，请重试", "The draft was not saved. Please try again."),
            )
        },
    )
    init {
        app.applicationScope.launch {
            for (command in commands) try {
                command()
            } catch (failure: Exception) {
                error.value = userFacingMessage(failure, localizedText("更改未保存，请重试", "Changes were not saved. Please try again."))
            }
        }
        dispatch {
            app.chatRuntime.ready()
            app.modelSettings.seedDebugDefaults()
            val remembered = app.appearance.currentConversation.first()?.let { app.conversations.conversation(it) }
            val conversation = remembered ?: app.conversations.observeConversations().first().firstOrNull()
                ?: createConversationWithDefaults()
            app.modelSettings.migrateLegacyReasoningEffort(conversation.reasoningEffort)
            activate(conversation)
        }
    }
    private fun dispatch(block: suspend () -> Unit) {
        if (commands.trySend(block).isFailure) {
            app.applicationScope.launch { commands.send(block) }
        }
    }
    private suspend fun activate(c: Conversation) {
        current.value?.takeIf { it != c.id }?.let { draftPersistence.flush(it) }
        if (drafts.value[c.id] == null) {
            drafts.update {
                it + (c.id to ComposerDraft(c.draft, c.attachments, c.draftSkills))
            }
        }
        current.value = c.id
        app.appearance.setCurrentConversation(c.id)
    }
    fun select(id: String) = dispatch { app.conversations.conversation(id)?.let { activate(it) } }
    fun newConversation() = dispatch { activate(createConversationWithDefaults()) }

    private suspend fun createConversationWithDefaults(): Conversation {
        val conversation = app.conversations.createConversation()
        app.skills.applyDefaults(conversation.id)
        return conversation
    }
    fun edit(id: String, draft: ComposerDraft) {
        val previousAttachments = drafts.value[id]?.attachments.orEmpty().map(AttachmentRef::uri).toSet()
        drafts.update { it + (id to draft) }
        draftPersistence.submit(
            conversationId = id,
            draft = draft,
            cleanupAttachments = previousAttachments != draft.attachments.map(AttachmentRef::uri).toSet(),
        )
    }
    fun send(id: String, reasoningEffort: String?, modelProfileId: String? = null) {
        val draft = drafts.value[id] ?: return
        val imageCount = draft.attachments.count(AttachmentRef::isImage)
        if (draft.text.isBlank() && imageCount == 0) return
        if (imageCount > 10) {
            error.value = localizedText("一次最多发送 10 张图片", "You can send up to 10 images at a time.")
            return
        }
        val cleared = ComposerDraft()
        drafts.update { it + (id to cleared) }
        dispatch {
            try {
                // Save the visible version, then remove the writer before enqueue clears the draft.
                // This prevents a delayed draft write from resurrecting already-sent text.
                draftPersistence.flushAndRemove(id)
                val activeSkills = (app.skills.conversationSkills(id) + draft.skills)
                    .distinctBy(SkillRef::skillId)
                app.chatRuntime.send(
                    id,
                    draft.text,
                    draft.attachments,
                    reasoningEffort,
                    modelProfileId,
                    activeSkills,
                )
                app.attachments.cleanup()
            }
            catch (e: Exception) {
                if (drafts.value[id] == cleared) {
                    drafts.update { it + (id to draft) }
                    draftPersistence.submit(id, draft, cleanupAttachments = false)
                }
                throw e
            }
        }
    }
    fun sendTranscription(id: String, text: String, reasoningEffort: String?, modelProfileId: String?) {
        if (text.isBlank()) return
        val currentDraft = drafts.value[id] ?: ComposerDraft()
        val mergedText = listOf(currentDraft.text.trim(), text.trim())
            .filter(String::isNotBlank)
            .joinToString("\n")
        edit(id, currentDraft.copy(text = mergedText))
        send(id, reasoningEffort, modelProfileId)
    }
    fun sendTranscriptionToNewConversation(text: String, reasoningEffort: String?, modelProfileId: String?) {
        if (text.isBlank()) return
        val normalized = text.trim()
        dispatch {
            // 只在转写成功后创建会话；发送失败时保留文字草稿，避免语音内容丢失。
            val conversation = app.conversations.createConversation()
            app.skills.applyDefaults(conversation.id)
            activate(conversation)
            val pendingDraft = ComposerDraft(text = normalized)
            drafts.update { it + (conversation.id to pendingDraft) }
            app.conversations.saveDraft(conversation.id, normalized, emptyList(), null, emptyList())
            val activeSkills = app.skills.conversationSkills(conversation.id)
            app.chatRuntime.send(
                conversation.id,
                normalized,
                emptyList(),
                reasoningEffort,
                modelProfileId,
                activeSkills,
            )
            drafts.update { it + (conversation.id to ComposerDraft()) }
        }
    }
    fun acceptShare(targetId: String?, share: IncomingShare) {
        if (app.attachments.takeForDraft(share.id) == null) return
        dispatch {
            try {
                val conversation = if (targetId == null) {
                    createConversationWithDefaults()
                } else {
                    app.conversations.conversation(targetId) ?: error(localizedText("找不到目标对话，请重新选择", "The target conversation was not found. Please choose again."))
                }
                val existing = drafts.value[conversation.id]
                    ?: ComposerDraft(conversation.draft, conversation.attachments, conversation.draftSkills)
                val mergedText = listOf(existing.text.trim(), share.text.trim())
                    .filter(String::isNotBlank).joinToString("\n")
                val merged = ComposerDraft(
                    text = mergedText,
                    attachments = (existing.attachments + share.attachments).distinctBy(AttachmentRef::uri),
                    skills = existing.skills,
                )
                drafts.update { it + (conversation.id to merged) }
                draftPersistence.submit(
                    conversation.id,
                    merged,
                    cleanupAttachments = existing.attachments.map(AttachmentRef::uri).toSet() !=
                        merged.attachments.map(AttachmentRef::uri).toSet(),
                )
                draftPersistence.flushAndRemove(conversation.id)
                activate(conversation)
            } catch (failure: Exception) {
                app.attachments.restore(share)
                throw failure
            }
        }
    }
    fun dismissShare(share: IncomingShare) {
        if (app.attachments.dismiss(share.id) == null) return
        app.applicationScope.launch { app.attachments.cleanup() }
    }
    fun addSkillOnce(id: String, skill: SkillRef) {
        dispatch {
            val draft = drafts.value[id] ?: ComposerDraft()
            val activeIds = (app.skills.conversationSkills(id) + draft.skills)
                .mapTo(linkedSetOf(), SkillRef::skillId)
            if (skill.skillId in activeIds) return@dispatch
            require(activeIds.size < SkillLimits.MAX_SELECTED) {
                localizedText("一次最多使用 ${SkillLimits.MAX_SELECTED} 个技能", "Use at most ${SkillLimits.MAX_SELECTED} Skills at a time.")
            }
            edit(id, draft.copy(skills = draft.skills + skill))
        }
    }
    fun removeSkillOnce(id: String, skillId: String) {
        val draft = drafts.value[id] ?: return
        edit(id, draft.copy(skills = draft.skills.filterNot { it.skillId == skillId }))
    }
    fun bindSkill(id: String, skill: SkillRef) = dispatch { app.skills.bindConversation(id, skill) }
    fun unbindSkill(id: String, skillId: String) = dispatch { app.skills.unbindConversation(id, skillId) }
    fun stop(id: String) { app.applicationScope.launch { app.chatRuntime.stop(id) } }
    fun compact(id: String) { app.applicationScope.launch { app.chatRuntime.compact(id) } }
    fun flushDrafts() { app.applicationScope.launch { draftPersistence.flushAll() } }
    fun rename(id: String, title: String) = dispatch { app.conversations.rename(id, title) }
    fun setPinned(id: String, pinned: Boolean) = dispatch { app.conversations.setPinned(id, pinned) }
    fun deleteConversation(id: String) = dispatch {
        require(id !in app.chatRuntime.active.value) { localizedText("这段对话正在回复，请停止后再删除", "This conversation is responding. Stop it before deleting.") }
        draftPersistence.discard(id)
        val deletingCurrent = current.value == id
        app.conversations.deleteConversation(id)
        drafts.update { it - id }
        if (deletingCurrent) {
            val replacement = app.conversations.observeConversations().first().firstOrNull()
                ?: createConversationWithDefaults()
            activate(replacement)
        }
        // 删除记录已完成；没有其他运行时再立即回收失去引用的附件和产物文件。
        if (app.chatRuntime.active.value.isEmpty()) runCatching { app.cleanupStorage() }
    }
}
