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
 */
class FcmService(
    private val deviceTokenService: DeviceTokenService,
    private val projectId: String = System.getenv("FCM_PROJECT_ID")
        ?: error("FCM_PROJECT_ID env var is not set"),
    serviceAccountJson: String = System.getenv("FCM_SERVICE_ACCOUNT_JSON")
        ?: error("FCM_SERVICE_ACCOUNT_JSON env var is not set"),
) {
    private val log = LoggerFactory.getLogger(FcmService::class.java)

    private val credentials: GoogleCredentials = GoogleCredentials
        .fromStream(serviceAccountJson.byteInputStream())
        .createScoped(FCM_SCOPE)

    private val httpClient = HttpClient(CIO)

    suspend fun sendToUser(
        userId: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val tokens = deviceTokenService.listByUser(userId)
        if (tokens.isEmpty()) {
            log.info("No device tokens for userId=$userId, skipping push")
            return
        }
        tokens.forEach { token -> sendToToken(token, title, body, data) }
    }

    suspend fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val accessToken = withContext(Dispatchers.IO) {
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }
        val payload = buildMessagePayload(token, title, body, data)
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

    private fun buildMessagePayload(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("notification") {
                put("title", title)
                put("body", body)
            }
            if (data.isNotEmpty()) {
                putJsonObject("data") {
                    data.forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }

    private companion object {
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
