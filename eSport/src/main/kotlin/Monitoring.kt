package com.sportenth

import com.sportenth.data.util.maskSensitive
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.userAgent
import io.ktor.util.AttributeKey
import java.util.UUID
import org.slf4j.event.Level

private val StartTimeKey = AttributeKey<Long>("RequestStartNanos")

fun Application.configureMonitoring() {
    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader("X-Request-Id")
    }

    // Кэширует тело запроса — нужен, чтобы перечитать body для логирования при 4xx/5xx.
    install(DoubleReceive)

    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/") }
        callIdMdc("call_id")

        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.attributes.getOrNull(StartTimeKey)?.let {
                "${(System.nanoTime() - it) / 1_000_000}ms"
            } ?: "?"
            "HTTP $method $path → ${status?.value ?: "?"} in $duration"
        }

        mdc("http_method") { it.request.httpMethod.value }
        mdc("http_path") { it.request.path() }
        mdc("http_status") { it.response.status()?.value?.toString() }
        mdc("duration_ms") { call ->
            call.attributes.getOrNull(StartTimeKey)?.let { ((System.nanoTime() - it) / 1_000_000).toString() }
        }
        mdc("user_id") { it.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() }
        mdc("remote_host") { it.request.local.remoteHost }
        mdc("user_agent") { it.request.userAgent() }
        mdc("query") { it.request.queryString().takeIf { q -> q.isNotBlank() } }
    }

    // Засекаем старт запроса для duration_ms.
    intercept(ApplicationCallPipeline.Setup) {
        call.attributes.put(StartTimeKey, System.nanoTime())
    }

    // При ответе 4xx/5xx — дологируем тело запроса (с маскировкой) для воспроизведения бага.
    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            proceed()
        } finally {
            val status = call.response.status()?.value ?: 0
            if (status >= 400 && shouldLogBody(call.request.httpMethod, call.request.contentType())) {
                runCatching {
                    val body = call.receiveText()
                    if (body.isNotEmpty()) {
                        call.application.log.warn(
                            "Error body for ${call.request.httpMethod.value} ${call.request.path()}: ${maskSensitive(body)}"
                        )
                    }
                }
            }
        }
    }
}

private fun shouldLogBody(method: HttpMethod, contentType: ContentType): Boolean {
    if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Delete) return false
    return contentType.match(ContentType.Application.Json) || contentType.match(ContentType.Text.Plain)
}
