package xyz.chouxuewei.mobile_agent.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "skills",
    indices = [Index(value = ["slashName"], unique = true), Index("updatedAt")],
)
internal data class SkillEntity(
    @androidx.room.PrimaryKey val id: String,
    val slashName: String,
    val displayName: String,
    val description: String,
    val activeVersionId: String,
    val enabled: Boolean,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "skill_versions",
    indices = [Index(value = ["skillId", "version"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = SkillEntity::class,
        parentColumns = ["id"],
        childColumns = ["skillId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class SkillVersionEntity(
    @androidx.room.PrimaryKey val id: String,
    val skillId: String,
    val version: Int,
    val description: String,
    val instructions: String,
    val manifestJson: String,
    val contentHash: String,
    val createdAt: Long,
)

@Entity(
    tableName = "conversation_skills",
    primaryKeys = ["conversationId", "skillId"],
    indices = [Index("skillId"), Index("versionId")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SkillEntity::class,
            parentColumns = ["id"],
            childColumns = ["skillId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SkillVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
internal data class ConversationSkillEntity(
    val conversationId: String,
    val skillId: String,
    val versionId: String,
    val boundAt: Long,
)

internal data class SkillSummaryProjection(
    val id: String,
    val slashName: String,
    val displayName: String,
    val description: String,
    val activeVersionId: String,
    val enabled: Boolean,
    val source: String,
    val updatedAt: Long,
    val version: Int,
)

internal data class SkillRefProjection(
    val skillId: String,
    val versionId: String,
    val slashName: String,
    val displayName: String,
)

internal data class ResolvedSkillProjection(
    val versionId: String,
    val skillId: String,
    val description: String,
    val instructions: String,
)

internal data class SkillDraftProjection(
    val slashName: String,
    val displayName: String,
    val description: String,
    val instructions: String,
)

@Dao
internal interface SkillDao {
    @Query(
        """
        SELECT s.id, s.slashName, s.displayName, s.description, s.activeVersionId,
               s.enabled, s.source, s.updatedAt, v.version
        FROM skills s JOIN skill_versions v ON v.id=s.activeVersionId
        WHERE s.deletedAt IS NULL
        ORDER BY s.updatedAt DESC, s.slashName
        """,
    )
    fun observeSummaries(): Flow<List<SkillSummaryProjection>>

    @Query("SELECT * FROM skills WHERE id=:id AND deletedAt IS NULL LIMIT 1")
    suspend fun skill(id: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE slashName=:slashName AND deletedAt IS NULL LIMIT 1")
    suspend fun skillBySlashName(slashName: String): SkillEntity?

    @Query("SELECT MAX(version) FROM skill_versions WHERE skillId=:skillId")
    suspend fun latestVersion(skillId: String): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(skill: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: SkillVersionEntity)

    @Query(
        """
        UPDATE skills SET slashName=:slashName, displayName=:displayName, description=:description,
            activeVersionId=:activeVersionId, updatedAt=:updatedAt
        WHERE id=:skillId AND deletedAt IS NULL
        """,
    )
    suspend fun update(
        skillId: String,
        slashName: String,
        displayName: String,
        description: String,
        activeVersionId: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE skills SET enabled=:enabled, updatedAt=:updatedAt WHERE id=:skillId AND deletedAt IS NULL")
    suspend fun setEnabled(skillId: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        """
        UPDATE skills
        SET slashName=slashName || '-deleted-' || id, enabled=0,
            deletedAt=:deletedAt, updatedAt=:deletedAt
        WHERE id=:skillId AND deletedAt IS NULL
        """,
    )
    suspend fun softDelete(skillId: String, deletedAt: Long): Int

    @Query(
        """
        SELECT s.slashName, s.displayName, v.description, v.instructions
        FROM skills s JOIN skill_versions v ON v.id=s.activeVersionId
        WHERE s.id=:skillId AND s.deletedAt IS NULL
        LIMIT 1
        """,
    )
    suspend fun draft(skillId: String): SkillDraftProjection?

    @Query(
        """
        SELECT id AS versionId, skillId, description, instructions
        FROM skill_versions WHERE id IN (:versionIds)
        """,
    )
    suspend fun resolvedVersions(versionIds: List<String>): List<ResolvedSkillProjection>

    @Query(
        """
        SELECT s.id AS skillId, v.id AS versionId, s.slashName, s.displayName
        FROM conversation_skills cs
        JOIN skills s ON s.id=cs.skillId
        JOIN skill_versions v ON v.id=cs.versionId
        WHERE cs.conversationId=:conversationId AND s.deletedAt IS NULL AND s.enabled=1
        ORDER BY cs.boundAt, s.slashName
        """,
    )
    fun observeConversationRefs(conversationId: String): Flow<List<SkillRefProjection>>

    @Query(
        """
        SELECT s.id AS skillId, v.id AS versionId, s.slashName, s.displayName
        FROM conversation_skills cs
        JOIN skills s ON s.id=cs.skillId
        JOIN skill_versions v ON v.id=cs.versionId
        WHERE cs.conversationId=:conversationId AND s.deletedAt IS NULL AND s.enabled=1
        ORDER BY cs.boundAt, s.slashName
        """,
    )
    suspend fun conversationRefs(conversationId: String): List<SkillRefProjection>

    @Query(
        """
        SELECT id AS skillId, activeVersionId AS versionId, slashName, displayName
        FROM skills
        WHERE id IN (:skillIds) AND deletedAt IS NULL AND enabled=1
        """,
    )
    suspend fun activeRefs(skillIds: List<String>): List<SkillRefProjection>

    @Upsert
    suspend fun bind(entity: ConversationSkillEntity)

    @Query("DELETE FROM conversation_skills WHERE conversationId=:conversationId AND skillId=:skillId")
    suspend fun unbind(conversationId: String, skillId: String): Int
}
