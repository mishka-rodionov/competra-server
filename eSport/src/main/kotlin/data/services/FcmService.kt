package com.competra.data.services

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Отправляет push-уведомления через Firebase Cloud Messaging HTTP v1 API.
 *
 * Авторизация — OAuth2 access_token, получается из service-account JSON через
 * [GoogleCredentials]; библиотека сама кеширует токен и обновляет его перед истечением.
 *
 * Если переменные окружения `FCM_PROJECT_ID` или `FCM_SERVICE_ACCOUNT_JSON` не заданы
 * (или содержимое битое) — сервис стартует в **disabled-режиме**: попытки отправки
 * логируются как warning и тихо завершаются без сетевого вызова. Это намеренно:
 * push — вспомогательная фича, её недоконфигурированность не должна валить весь backend.
 */
class FcmService(
    private val deviceTokenService: DeviceTokenService,
    private val projectId: String? = System.getenv("FCM_PROJECT_ID").takeUnless { it.isNullOrBlank() },
    serviceAccountJson: String? = System.getenv("FCM_SERVICE_ACCOUNT_JSON").takeUnless { it.isNullOrBlank() },
) {
    private val log = LoggerFactory.getLogger(FcmService::class.java)

    private val credentials: GoogleCredentials? = initCredentials(serviceAccountJson)

    private val httpClient = HttpClient(CIO)

    private val isEnabled: Boolean get() = credentials != null && !projectId.isNullOrBlank()

    init {
        if (isEnabled) {
            log.info("FCM enabled, projectId=$projectId")
        } else {
            log.warn(
                "FCM is DISABLED: " +
                    "projectIdSet=${!projectId.isNullOrBlank()}, " +
                    "credentialsLoaded=${credentials != null}. " +
                    "Set FCM_PROJECT_ID and FCM_SERVICE_ACCOUNT_JSON to enable push."
            )
        }
    }

    suspend fun sendToUser(
        userId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        includeNotificationBlock: Boolean = true,
    ) {
        if (!isEnabled) {
            log.debug("FCM disabled, skipping push to userId=$userId")
            return
        }
        val tokens = deviceTokenService.listByUser(userId)
        if (tokens.isEmpty()) {
            log.info("No device tokens for userId=$userId, skipping push")
            return
        }
        tokens.forEach { token -> sendToToken(token, title, body, data, includeNotificationBlock) }
    }

    /**
     * @param includeNotificationBlock Если `true` (по умолчанию), payload содержит FCM `notification`-блок —
     *   в фоне Android рисует уведомление сам, минуя `onMessageReceived` (штатное поведение FCM). Категории
     *   пушей с клиентской фильтрацией по настройкам пользователя и/или deep-link'ом ДОЛЖНЫ передавать `false`:
     *   тогда payload data-only, и `onMessageReceived` гарантированно вызывается независимо от состояния приложения.
     */
    suspend fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        includeNotificationBlock: Boolean = true,
    ) {
        val creds = credentials ?: run {
            log.debug("FCM disabled, skipping push to token=${token.take(12)}…")
            return
        }
        val accessToken = withContext(Dispatchers.IO) {
            creds.refreshIfExpired()
            creds.accessToken.tokenValue
        }
        val payload = buildMessagePayload(token, title, body, data, includeNotificationBlock)
        val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"

        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        when (response.status) {
            HttpStatusCode.OK -> log.info("FCM push delivered to token=${token.take(12)}…")
            HttpStatusCode.NotFound, HttpStatusCode.BadRequest -> {
                // UNREGISTERED / INVALID_ARGUMENT — токен мёртв, чистим.
                log.warn("FCM token invalid (${response.status}), removing: ${token.take(12)}… body=${response.bodyAsText()}")
                deviceTokenService.delete(token)
            }
            else -> log.error("FCM push failed: ${response.status} body=${response.bodyAsText()}")
        }
    }

    private fun initCredentials(serviceAccountJson: String?): GoogleCredentials? {
        if (serviceAccountJson.isNullOrBlank()) return null
        return runCatching {
            GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
                .createScoped(FCM_SCOPE)
        }.onFailure {
            LoggerFactory.getLogger(FcmService::class.java)
                .error("Failed to initialize GoogleCredentials from FCM_SERVICE_ACCOUNT_JSON, FCM disabled", it)
        }.getOrNull()
    }

    private fun buildMessagePayload(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
        includeNotificationBlock: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            if (includeNotificationBlock) {
                putJsonObject("notification") {
                    put("title", title)
                    put("body", body)
                }
            }
            if (!includeNotificationBlock || data.isNotEmpty()) {
                putJsonObject("data") {
                    // data-only payload кладёт title/body сюда же — CompetraMessagingService.onMessageReceived
                    // читает их как fallback, когда notification-блока нет.
                    if (!includeNotificationBlock) {
                        put("title", title)
                        put("body", body)
                    }
                    data.forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }

    private companion object {
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
