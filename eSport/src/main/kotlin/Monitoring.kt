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
import org.slf4j.MDC
import org.slf4j.event.Level

private val StartTimeKey = AttributeKey<Long>("RequestStartNanos")

fun Application.configureMonitoring() {
    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader("X-Request-Id")
    }

    install(DoubleReceive)

    // CallLogging оставлен ради MDC-обогащения дочерних логов (call_id, http_path, ...)
    // во всех log.info внутри обработчиков. Уровень TRACE отключает его финальную строку
    // в logback (root = INFO), чтобы не дублировать наш собственный итоговый лог.
    // Здесь намеренно нет mdc("duration_ms") и mdc("http_status") — они вычисляются
    // CallLogging один раз в начале запроса, когда статус ещё пуст, а duration ≈ 0.
    install(CallLogging) {
        level = Level.TRACE
        filter { it.request.path().startsWith("/") }
        callIdMdc("call_id")
        mdc("http_method") { it.request.httpMethod.value }
        mdc("http_path") { it.request.path() }
        mdc("user_id") { it.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString() }
        mdc("remote_host") { it.request.local.remoteHost }
        mdc("user_agent") { it.request.userAgent() }
        mdc("query") { it.request.queryString().takeIf { q -> q.isNotBlank() } }
    }

    intercept(ApplicationCallPipeline.Setup) {
        call.attributes.put(StartTimeKey, System.nanoTime())
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            proceed()
        } finally {
            val durationMs = call.attributes.getOrNull(StartTimeKey)
                ?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0
            val status = call.response.status()?.value ?: 0
            val method = call.request.httpMethod.value
            val path = call.request.path()

            // Финальная строка пишется здесь, после proceed() — на этот момент status
            // и duration реальные. MDC.put/remove делает их JSON-полями ровно для этой записи,
            // не задевая дочерние логи внутри запроса.
            MDC.put("http_status", status.toString())
            MDC.put("duration_ms", durationMs.toString())
            try {
                call.application.log.info("HTTP $method $path → $status in ${durationMs}ms")
            } finally {
                MDC.remove("http_status")
                MDC.remove("duration_ms")
            }

            if (status >= 400 && shouldLogBody(call.request.httpMethod, call.request.contentType())) {
                runCatching {
                    val body = call.receiveText()
                    if (body.isNotEmpty()) {
                        MDC.put("http_status", status.toString())
                        try {
                            call.application.log.warn(
                                "Error body for $method $path: ${maskSensitive(body)}"
                            )
                        } finally {
                            MDC.remove("http_status")
                        }
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
