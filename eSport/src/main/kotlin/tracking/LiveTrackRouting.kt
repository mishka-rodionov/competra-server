package com.competra.tracking

import com.competra.data.response.base.CommonModel
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Роуты онлайн-трекинга (монтируются под `/api/live-track`).
 *
 * Публичные (зрители, в т.ч. гости):
 * - `GET /competitions/{competitionId}/distances` — дистанции, по которым есть треки;
 * - `GET /distances/{distanceId}/live?since={cursor}` — живой снимок из памяти;
 * - `GET /distances/{distanceId}/tracks` — архив треков.
 *
 * Бегун (JWT):
 * - `POST /sessions` — старт/возобновление;
 * - `POST /sessions/{id}/points` — батч точек;
 * - `POST /sessions/{id}/stop` — ручная остановка.
 */
fun Route.liveTrackRoutes(service: LiveTrackService) {
    get("/competitions/{competitionId}/distances") {
        val competitionId = call.parameters["competitionId"] ?: throw BadRequestException("competitionId is required")
        call.respondOk(service.distances(competitionId))
    }

    get("/distances/{distanceId}/live") {
        call.respondOk(service.live(call.distanceId(), call.request.queryParameters["since"]))
    }

    get("/distances/{distanceId}/tracks") {
        call.respondOk(service.tracks(call.distanceId()))
    }

    authenticate("auth-jwt") {
        post("/sessions") {
            val userId = call.userId() ?: return@post call.respondUnauthorized()
            call.respondOk(service.start(userId, call.receive<StartSessionRequest>()))
        }

        post("/sessions/{sessionId}/points") {
            val userId = call.userId() ?: return@post call.respondUnauthorized()
            val sessionId = call.parameters["sessionId"] ?: throw BadRequestException("sessionId is required")
            call.respondOk(service.appendPoints(userId, sessionId, call.receive<PointsBatchRequest>()))
        }

        post("/sessions/{sessionId}/stop") {
            val userId = call.userId() ?: return@post call.respondUnauthorized()
            val sessionId = call.parameters["sessionId"] ?: throw BadRequestException("sessionId is required")
            call.respondOk(service.stop(userId, sessionId))
        }
    }
}

private fun RoutingCall.distanceId(): Long =
    parameters["distanceId"]?.toLongOrNull() ?: throw BadRequestException("distanceId must be a number")

private fun RoutingCall.userId(): String? =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()

private suspend fun RoutingCall.respondOk(result: Any) {
    respond(CommonModel<Any>().also { it.status = 1; it.result = result })
}

private suspend fun RoutingCall.respondUnauthorized() {
    respond(
        HttpStatusCode.Unauthorized,
        CommonModel<Any>().also { it.status = 0; it.errors = listOf(com.competra.data.response.base.BaseError(401, "Unauthorized")) }
    )
}
