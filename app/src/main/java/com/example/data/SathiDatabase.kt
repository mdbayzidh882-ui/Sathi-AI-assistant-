package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    val userName: String,
    val email: String,
    val plan: String,
    val redeemedCodeId: String,
    val planExpiresAt: Long,
    val banned: Boolean,
    val preferredLanguage: String,
    val preferredVoice: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "user_memories")
data class UserMemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val category: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "premium_codes")
data class PremiumCodeEntity(
    @PrimaryKey val id: String,
    val codeType: String,
    val status: String,
    val createdBy: String,
    val usedBy: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val id: String,
    val cameraEnabledForFree: Boolean,
    val longTermMemoryForFree: Boolean,
    val translationForFree: Boolean,
    val dayPlanningForFree: Boolean,
    val announcementText: String,
    val announcementActive: Boolean,
    val updatedBy: String,
    val updatedAt: Long
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey val id: String,
    val actorUid: String,
    val actorEmail: String,
    val action: String,
    val target: String,
    val details: String,
    val createdAt: Long
)

@Dao
interface SathiDao {
    @Query("SELECT * FROM user_profiles WHERE userId = :uid LIMIT 1")
    fun observeUserProfile(uid: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE userId = :uid LIMIT 1")
    suspend fun getUserProfileOnce(uid: String): UserProfileEntity?

    @Query("SELECT * FROM user_profiles ORDER BY updatedAt DESC")
    fun observeAllUsers(): Flow<List<UserProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(entity: UserProfileEntity)

    @Query(
        "UPDATE user_profiles SET userName = :name, preferredLanguage = :lang, preferredVoice = :voice, updatedAt = :updatedAt WHERE userId = :uid"
    )
    suspend fun updateUserPreferences(
        uid: String,
        name: String,
        lang: String,
        voice: String,
        updatedAt: Long
    )

    @Query(
        "UPDATE user_profiles SET plan = :plan, redeemedCodeId = :codeId, planExpiresAt = :expiresAt, updatedAt = :updatedAt WHERE userId = :uid"
    )
    suspend fun updateUserPlan(
        uid: String,
        plan: String,
        codeId: String,
        expiresAt: Long,
        updatedAt: Long
    )

    @Query(
        "UPDATE user_profiles SET plan = :plan, planExpiresAt = :expiresAt, banned = :banned, updatedAt = :updatedAt WHERE userId = :uid"
    )
    suspend fun adminUpdateUser(
        uid: String,
        plan: String,
        expiresAt: Long,
        banned: Boolean,
        updatedAt: Long
    )

    @Query("SELECT * FROM user_memories WHERE userId = :uid ORDER BY updatedAt DESC")
    fun observeMemories(uid: String): Flow<List<UserMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(entity: UserMemoryEntity)

    @Query(
        "UPDATE user_memories SET category = :category, title = :title, content = :content, updatedAt = :updatedAt WHERE id = :id AND userId = :uid"
    )
    suspend fun updateMemory(
        id: String,
        uid: String,
        category: String,
        title: String,
        content: String,
        updatedAt: Long
    )

    @Query("DELETE FROM user_memories WHERE id = :id AND userId = :uid")
    suspend fun deleteMemory(id: String, uid: String)

    @Query("DELETE FROM user_memories WHERE userId = :uid")
    suspend fun deleteAllMemoriesForUser(uid: String)

    @Query("SELECT * FROM app_config WHERE id = 'global' LIMIT 1")
    fun observeGlobalConfig(): Flow<AppConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlobalConfig(entity: AppConfigEntity)

    @Query("SELECT * FROM premium_codes ORDER BY createdAt DESC")
    fun observeAllCodes(): Flow<List<PremiumCodeEntity>>

    @Query("SELECT * FROM premium_codes WHERE id = :codeId LIMIT 1")
    suspend fun getCodeById(codeId: String): PremiumCodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCode(entity: PremiumCodeEntity)

    @Query(
        "UPDATE premium_codes SET status = :status, usedBy = :usedBy, updatedAt = :updatedAt WHERE id = :codeId"
    )
    suspend fun updateCodeStatus(codeId: String, status: String, usedBy: String, updatedAt: Long)

    @Query("SELECT * FROM activity_logs ORDER BY createdAt DESC LIMIT 50")
    fun observeActivityLogs(): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityLog(entity: ActivityLogEntity)
}

@Database(
    entities = [
        UserProfileEntity::class,
        UserMemoryEntity::class,
        PremiumCodeEntity::class,
        AppConfigEntity::class,
        ActivityLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SathiDatabase : RoomDatabase() {
    abstract fun sathiDao(): SathiDao

    companion object {
        @Volatile
        private var instance: SathiDatabase? = null

        fun getInstance(context: Context): SathiDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SathiDatabase::class.java,
                    "sathi_local_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
