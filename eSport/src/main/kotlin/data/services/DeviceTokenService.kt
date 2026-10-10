package com.competra.data.services

import com.competra.data.database.entity.DeviceTokens
import com.competra.data.requests.FcmTokenRequest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.competra.data.database.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

class DeviceTokenService {

    suspend fun upsert(userId: String, request: FcmTokenRequest) =
        ioTransaction {
            val now = System.currentTimeMillis()
            DeviceTokens.upsert(DeviceTokens.token) {
                it[token] = request.token
                it[DeviceTokens.userId] = userId
                it[platform] = request.platform
                it[appVersion] = request.appVersion
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

    suspend fun delete(token: String) =
        ioTransaction {
            DeviceTokens.deleteWhere { DeviceTokens.token eq token }
        }

    suspend fun deleteByUser(userId: String) =
        ioTransaction {
            DeviceTokens.deleteWhere { DeviceTokens.userId eq userId }
        }

    suspend fun listByUser(userId: String): List<String> =
        ioTransaction {
            DeviceTokens.selectAll()
                .where { DeviceTokens.userId eq userId }
                .map { it[DeviceTokens.token] }
        }
}
