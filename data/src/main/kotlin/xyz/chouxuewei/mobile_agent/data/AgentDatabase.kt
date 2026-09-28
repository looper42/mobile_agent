package xyz.chouxuewei.mobile_agent.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MessageSearchEntity::class, RunEntity::class, SnapshotEntity::class, ToolCallEntity::class, ArtifactEntity::class],
    version = 9,
    exportSchema = false,
)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun artifacts(): ArtifactDao
}
