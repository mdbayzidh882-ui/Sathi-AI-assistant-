package com.example.data

import com.example.model.ActivityLog
import com.example.model.AppConfig
import com.example.model.CodeType
import com.example.model.MemoryCategory
import com.example.model.PlanTier
import com.example.model.PremiumCode
import com.example.model.UserMemory
import com.example.model.UserProfile
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SathiLocalRepository(private val dao: SathiDao) {

    suspend fun ensureUserProfile(
        uid: String,
        displayName: String,
        email: String
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            if (dao.getUserProfileOnce(uid) == null) {
                val safeName = displayName.trim().ifBlank { "বন্ধু" }.take(60)
                dao.upsertUserProfile(
                    UserProfileEntity(
                        userId = uid,
                        userName = safeName,
                        email = email.trim().take(120),
                        plan = PlanTier.FREE.code,
                        redeemedCodeId = "",
                        planExpiresAt = 0L,
                        banned = false,
                        preferredLanguage = "bn",
                        preferredVoice = "female",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> =
        dao.observeUserProfile(uid).map { it?.toDomain() }

    suspend fun updateUserPreferences(
        uid: String,
        userName: String,
        languageCode: String,
        voiceCode: String
    ): Result<Unit> {
        return try {
            val safeName = userName.trim().ifBlank { "বন্ধু" }.take(60)
            val safeLang = if (languageCode == "en") "en" else "bn"
            val safeVoice = if (voiceCode == "male") "male" else "female"
            dao.updateUserPreferences(
                uid = uid,
                name = safeName,
                lang = safeLang,
                voice = safeVoice,
                updatedAt = System.currentTimeMillis()
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeMemories(uid: String): Flow<List<UserMemory>> =
        dao.observeMemories(uid).map { list -> list.map { it.toDomain() } }

    suspend fun addMemory(
        uid: String,
        category: MemoryCategory,
        title: String,
        content: String
    ): Result<Unit> {
        return try {
            val safeTitle = title.trim().take(100)
            val safeContent = content.trim().take(1000)
            require(safeTitle.isNotEmpty() && safeContent.isNotEmpty()) {
                "Memory title and content must not be blank"
            }
            val now = System.currentTimeMillis()
            dao.upsertMemory(
                UserMemoryEntity(
                    id = UUID.randomUUID().toString(),
                    userId = uid,
                    category = category.code,
                    title = safeTitle,
                    content = safeContent,
                    createdAt = now,
                    updatedAt = now
                )
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMemory(
        uid: String,
        memoryId: String,
        category: MemoryCategory,
        title: String,
        content: String
    ): Result<Unit> {
        return try {
            require(memoryId.isNotBlank())
            val safeTitle = title.trim().take(100)
            val safeContent = content.trim().take(1000)
            require(safeTitle.isNotEmpty() && safeContent.isNotEmpty()) {
                "Memory title and content must not be blank"
            }
            dao.updateMemory(
                id = memoryId,
                uid = uid,
                category = category.code,
                title = safeTitle,
                content = safeContent,
                updatedAt = System.currentTimeMillis()
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMemory(uid: String, memoryId: String): Result<Unit> {
        return try {
            require(memoryId.isNotBlank())
            dao.deleteMemory(memoryId, uid)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAllUserData(uid: String): Result<Unit> {
        return try {
            dao.deleteAllMemoriesForUser(uid)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeGlobalConfig(): Flow<AppConfig> =
        dao.observeGlobalConfig().map { it?.toDomain() ?: AppConfig() }

    suspend fun redeemPremiumCode(uid: String, rawCode: String): Result<PlanTier> {
        val sanitizedCode = rawCode.trim().uppercase()
        return try {
            if (sanitizedCode.isBlank() || !Regex("^[A-Za-z0-9_\\-]+$").matches(sanitizedCode)) {
                return Result.failure(IllegalArgumentException("INVALID_FORMAT"))
            }
            val codeDoc = dao.getCodeById(sanitizedCode)
                ?: return Result.failure(IllegalArgumentException("CODE_NOT_FOUND"))

            if (codeDoc.status != "active") {
                return Result.failure(IllegalStateException("CODE_INACTIVE_${codeDoc.status.uppercase()}"))
            }

            val codeType = CodeType.fromCode(codeDoc.codeType)
            val now = System.currentTimeMillis()
            val targetPlan: PlanTier
            val expiresAt: Long

            if (codeType == CodeType.OWNER) {
                targetPlan = PlanTier.OWNER
                expiresAt = 0L
            } else {
                targetPlan = PlanTier.PREMIUM
                expiresAt = now + codeType.durationMs
                dao.updateCodeStatus(
                    codeId = sanitizedCode,
                    status = "used",
                    usedBy = uid,
                    updatedAt = now
                )
            }

            dao.updateUserPlan(
                uid = uid,
                plan = targetPlan.code,
                codeId = sanitizedCode,
                expiresAt = expiresAt,
                updatedAt = now
            )
            Result.success(targetPlan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isUserAdminDocPresent(): Boolean = true

    fun observeAllCodes(): Flow<List<PremiumCode>> =
        dao.observeAllCodes().map { list -> list.map { it.toDomain() } }

    fun observeAllUsers(): Flow<List<UserProfile>> =
        dao.observeAllUsers().map { list -> list.map { it.toDomain() } }

    fun observeActivityLogs(): Flow<List<ActivityLog>> =
        dao.observeActivityLogs().map { list -> list.map { it.toDomain() } }

    suspend fun generatePremiumCode(
        adminUid: String,
        adminEmail: String,
        codeType: CodeType,
        note: String
    ): Result<String> {
        val codeId = generateRandomCode(codeType)
        return try {
            val now = System.currentTimeMillis()
            dao.upsertCode(
                PremiumCodeEntity(
                    id = codeId,
                    codeType = codeType.code,
                    status = "active",
                    createdBy = adminUid,
                    usedBy = "",
                    note = note.trim().take(120),
                    createdAt = now,
                    updatedAt = now
                )
            )
            writeActivityLog(
                adminUid = adminUid,
                adminEmail = adminEmail,
                action = "GENERATE_CODE",
                target = codeId,
                details = "Generated ${codeType.labelEn} code"
            )
            Result.success(codeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokePremiumCode(
        adminUid: String,
        adminEmail: String,
        codeId: String
    ): Result<Unit> {
        return try {
            dao.updateCodeStatus(
                codeId = codeId,
                status = "revoked",
                usedBy = "",
                updatedAt = System.currentTimeMillis()
            )
            writeActivityLog(
                adminUid = adminUid,
                adminEmail = adminEmail,
                action = "REVOKE_CODE",
                target = codeId,
                details = "Revoked activation code"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminUpdateUser(
        adminUid: String,
        adminEmail: String,
        targetUid: String,
        newPlan: PlanTier,
        banned: Boolean
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            val expiresAt = when (newPlan) {
                PlanTier.FREE -> 0L
                PlanTier.PREMIUM -> now + 30L * 24 * 60 * 60 * 1000
                PlanTier.OWNER -> 0L
            }
            dao.adminUpdateUser(
                uid = targetUid,
                plan = newPlan.code,
                expiresAt = expiresAt,
                banned = banned,
                updatedAt = now
            )
            writeActivityLog(
                adminUid = adminUid,
                adminEmail = adminEmail,
                action = "UPDATE_USER",
                target = targetUid,
                details = "Plan=${newPlan.code}, banned=$banned"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun adminUpdateGlobalConfig(
        adminUid: String,
        adminEmail: String,
        config: AppConfig
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            dao.upsertGlobalConfig(
                AppConfigEntity(
                    id = "global",
                    cameraEnabledForFree = config.cameraEnabledForFree,
                    longTermMemoryForFree = config.longTermMemoryForFree,
                    translationForFree = config.translationForFree,
                    dayPlanningForFree = config.dayPlanningForFree,
                    announcementText = config.announcementText.trim().take(500),
                    announcementActive = config.announcementActive,
                    updatedBy = adminUid,
                    updatedAt = now
                )
            )
            writeActivityLog(
                adminUid = adminUid,
                adminEmail = adminEmail,
                action = "UPDATE_CONFIG",
                target = "global",
                details = "Updated feature flags & announcement"
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun writeActivityLog(
        adminUid: String,
        adminEmail: String,
        action: String,
        target: String,
        details: String
    ) {
        dao.insertActivityLog(
            ActivityLogEntity(
                id = UUID.randomUUID().toString(),
                actorUid = adminUid,
                actorEmail = adminEmail.take(120),
                action = action.take(80),
                target = target.take(120),
                details = details.take(400),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun generateRandomCode(type: CodeType): String {
        val prefix = when (type) {
            CodeType.ONE_MONTH -> "SATHI-1M"
            CodeType.SIX_MONTHS -> "SATHI-6M"
            CodeType.ONE_YEAR -> "SATHI-1Y"
            CodeType.OWNER -> "SATHI-OWNER"
        }
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        fun chunk(len: Int) = (1..len).map { chars[random.nextInt(chars.length)] }.joinToString("")
        return "$prefix-${chunk(4)}-${chunk(4)}-${chunk(4)}"
    }

    private fun UserProfileEntity.toDomain() = UserProfile(
        id = userId,
        userId = userId,
        userName = userName,
        email = email,
        plan = plan,
        redeemedCodeId = redeemedCodeId,
        planExpiresAt = planExpiresAt,
        banned = banned,
        preferredLanguage = preferredLanguage,
        preferredVoice = preferredVoice,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun UserMemoryEntity.toDomain() = UserMemory(
        id = id,
        userId = userId,
        category = category,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun PremiumCodeEntity.toDomain() = PremiumCode(
        id = id,
        codeType = codeType,
        status = status,
        createdBy = createdBy,
        usedBy = usedBy,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun AppConfigEntity.toDomain() = AppConfig(
        id = id,
        cameraEnabledForFree = cameraEnabledForFree,
        longTermMemoryForFree = longTermMemoryForFree,
        translationForFree = translationForFree,
        dayPlanningForFree = dayPlanningForFree,
        announcementText = announcementText,
        announcementActive = announcementActive,
        updatedBy = updatedBy,
        updatedAt = updatedAt
    )

    private fun ActivityLogEntity.toDomain() = ActivityLog(
        id = id,
        actorUid = actorUid,
        actorEmail = actorEmail,
        action = action,
        target = target,
        details = details,
        createdAt = createdAt
    )

    companion object {
        const val LOCAL_OWNER_UID = "local_owner"
        const val OWNER_EMAIL = "mdbayzidh882@gmail.com"
        const val OWNER_DEFAULT_NAME = "মোহাম্মদ বাইজিদ হাসান"
    }
}
