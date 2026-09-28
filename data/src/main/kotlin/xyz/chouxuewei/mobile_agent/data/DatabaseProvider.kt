package xyz.chouxuewei.mobile_agent.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DatabaseProvider {
    @Volatile private var instance: AgentDatabase? = null
    fun get(context: Context): AgentDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "mobile-agent.db")
            .addMigrations(
                CHAT_MIGRATION,
                REASONING_MIGRATION,
                COMPOSER_REASONING_MIGRATION,
                TOOL_CALL_MIGRATION,
                ARTIFACT_MIGRATION,
                CONVERSATION_PIN_MIGRATION,
                MESSAGE_SEARCH_MIGRATION,
                REMOVE_LEGACY_AGENT_MIGRATION,
                SKILLS_MIGRATION,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createMessageSearchTriggers(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Idempotent repair for databases created before the FTS triggers existed.
                    createMessageSearchTriggers(db)
                }
            })
            .build().also { instance = it }
    }
}

/** 只添加聊天表，原 tasks/steps 的结构和数据完全不动；不启用破坏性回退。 */
val CHAT_MIGRATION = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, draft TEXT NOT NULL, attachments TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, attachments TEXT NOT NULL, version INTEGER NOT NULL, error TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_conversationId_sequence ON messages(conversationId, sequence)")
        db.execSQL("CREATE TABLE IF NOT EXISTS runs (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, triggerMessageId TEXT NOT NULL, replyMessageId TEXT NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, error TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runs_conversationId ON runs(conversationId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS context_snapshots (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, boundary INTEGER NOT NULL, sourceVersions TEXT NOT NULL, summary TEXT NOT NULL, model TEXT NOT NULL, inputTokensBefore INTEGER NOT NULL, inputTokensAfter INTEGER NOT NULL, createdAt INTEGER NOT NULL, formatVersion INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_context_snapshots_conversationId ON context_snapshots(conversationId)")
    }
}

/** 思考流与最终正文分别保存，页面折叠或进程重启后仍可恢复。 */
val REASONING_MIGRATION = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN reasoningDurationMillis INTEGER")
    }
}

/** 每个会话保存输入区选择的思考强度，重新打开应用后恢复原选择。 */
val COMPOSER_REASONING_MIGRATION = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
    }
}

/** 工具调用先于执行落盘，进程中断后保留事实但不自动重放外部动作。 */
val TOOL_CALL_MIGRATION = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tool_calls (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, runId TEXT NOT NULL, replyMessageId TEXT NOT NULL, toolId TEXT NOT NULL, argumentsJson TEXT NOT NULL, status TEXT NOT NULL, result TEXT, displaySummary TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_conversationId ON tool_calls(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_runId ON tool_calls(runId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_calls_replyMessageId ON tool_calls(replyMessageId)")
    }
}

/** 产物表只保存索引与稳定 URI，文件正文继续留在应用受控目录。 */
val ARTIFACT_MIGRATION = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS artifacts (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, runId TEXT NOT NULL, replyMessageId TEXT NOT NULL, sourceToolCallId TEXT, name TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, contentUri TEXT NOT NULL, storagePath TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_conversationId ON artifacts(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_replyMessageId ON artifacts(replyMessageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_sourceToolCallId ON artifacts(sourceToolCallId)")
    }
}

/** 置顶只改变历史列表顺序，不修改会话内容或最近更新时间。 */
val CONVERSATION_PIN_MIGRATION = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Full-text history index. Triggers keep the narrow mirror transactionally aligned with messages
 * and titles, including rows inserted through future store implementations.
 */
val MESSAGE_SEARCH_MIGRATION = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING FTS4(messageId, conversationId, conversationTitle, role, text, tokenize=unicode61)")
        db.execSQL(
            """
            INSERT INTO message_search(rowid, messageId, conversationId, conversationTitle, role, text)
            SELECT messages.rowid, messages.id, messages.conversationId, conversations.title, messages.role, messages.text
            FROM messages INNER JOIN conversations ON conversations.id=messages.conversationId
            """.trimIndent(),
        )
        createMessageSearchTriggers(db)
    }
}

/** The production app has one execution path; obsolete task/step loop data is no longer retained. */
val REMOVE_LEGACY_AGENT_MIGRATION = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS steps")
        db.execSQL("DROP TABLE IF EXISTS tasks")
    }
}

/** Adds versioned, user-owned Skills without rewriting existing messages or drafts. */
val SKILLS_MIGRATION = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN draftSkills TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE messages ADD COLUMN skills TEXT NOT NULL DEFAULT '[]'")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skills (
              id TEXT NOT NULL PRIMARY KEY,
              slashName TEXT NOT NULL,
              displayName TEXT NOT NULL,
              description TEXT NOT NULL,
              activeVersionId TEXT NOT NULL,
              enabled INTEGER NOT NULL,
              source TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              deletedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skills_slashName ON skills(slashName)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_updatedAt ON skills(updatedAt)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skill_versions (
              id TEXT NOT NULL PRIMARY KEY,
              skillId TEXT NOT NULL,
              version INTEGER NOT NULL,
              description TEXT NOT NULL,
              instructions TEXT NOT NULL,
              manifestJson TEXT NOT NULL,
              contentHash TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              FOREIGN KEY(skillId) REFERENCES skills(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skill_versions_skillId_version ON skill_versions(skillId,version)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_skills (
              conversationId TEXT NOT NULL,
              skillId TEXT NOT NULL,
              versionId TEXT NOT NULL,
              boundAt INTEGER NOT NULL,
              PRIMARY KEY(conversationId,skillId),
              FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE,
              FOREIGN KEY(skillId) REFERENCES skills(id) ON DELETE CASCADE,
              FOREIGN KEY(versionId) REFERENCES skill_versions(id) ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_skills_versionId ON conversation_skills(versionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_skills_skillId ON conversation_skills(skillId)")
    }
}

internal fun createMessageSearchTriggers(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_after_insert AFTER INSERT ON messages BEGIN
          INSERT INTO message_search(rowid, messageId, conversationId, conversationTitle, role, text)
          VALUES (new.rowid, new.id, new.conversationId,
                  (SELECT title FROM conversations WHERE id=new.conversationId), new.role, new.text);
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_after_update AFTER UPDATE OF id, conversationId, role, text ON messages BEGIN
          DELETE FROM message_search WHERE rowid=old.rowid;
          INSERT INTO message_search(rowid, messageId, conversationId, conversationTitle, role, text)
          VALUES (new.rowid, new.id, new.conversationId,
                  (SELECT title FROM conversations WHERE id=new.conversationId), new.role, new.text);
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_after_delete AFTER DELETE ON messages BEGIN
          DELETE FROM message_search WHERE rowid=old.rowid;
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_title_after_update AFTER UPDATE OF title ON conversations BEGIN
          UPDATE message_search SET conversationTitle=new.title WHERE conversationId=new.id;
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_conversation_after_delete AFTER DELETE ON conversations BEGIN
          DELETE FROM message_search WHERE conversationId=old.id;
        END
        """.trimIndent(),
    )
}
