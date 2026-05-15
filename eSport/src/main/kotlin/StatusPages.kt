package com.competra

import com.competra.data.exception.ConflictException
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond

fun Application.configureStatusPages() {
    install(StatusPages) {
        // Server-wins конфликт синхронизации.
        // Контракт идентичен прежнему respondConflictAware из OrienteeringRouting.kt:
        // клиент читает result как актуальную серверную запись и перезатирает локалку.
        exception<ConflictException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                CommonModel<Any>().also {
                    it.status = 0
                    it.result = cause.currentResponse
                    it.errors = listOf(BaseError(409, "Server record is newer"))
                }
            )
        }

        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also {
                    it.status = 0
                    it.errors = listOf(BaseError(400, cause.message ?: "Bad request"))
                }
            )
        }

        exception<NotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                CommonModel<Any>().also {
                    it.status = 0
                    it.errors = listOf(BaseError(404, cause.message ?: "Not found"))
                }
            )
        }

        exception<Throwable> { call, cause ->
            val traceId = call.callId ?: "unknown"
            call.application.log.error(
                "Unhandled exception traceId=$traceId method=${call.request.httpMethod.value} path=${call.request.path()}",
                cause
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                CommonModel<Map<String, String>>().also {
                    it.status = 0
                    it.errors = listOf(BaseError(500, cause.message ?: "Internal server error"))
                    it.result = mapOf("traceId" to traceId)
                }
            )
        }
    }
}
