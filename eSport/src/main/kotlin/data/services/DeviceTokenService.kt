package com.competra.data.services

import com.competra.data.database.entity.DeviceTokens
import com.competra.data.requests.FcmTokenRequest
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert

class DeviceTokenService {

    suspend fun upsert(userId: String, request: FcmTokenRequest) =
        newSuspendedTransaction(Dispatchers.IO) {
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
        newSuspendedTransaction(Dispatchers.IO) {
            DeviceTokens.deleteWhere { DeviceTokens.token eq token }
        }

    suspend fun deleteByUser(userId: String) =
        newSuspendedTransaction(Dispatchers.IO) {
            DeviceTokens.deleteWhere { DeviceTokens.userId eq userId }
        }

    suspend fun listByUser(userId: String): List<String> =
        newSuspendedTransaction(Dispatchers.IO) {
            DeviceTokens.selectAll()
                .where { DeviceTokens.userId eq userId }
                .map { it[DeviceTokens.token] }
        }
}
