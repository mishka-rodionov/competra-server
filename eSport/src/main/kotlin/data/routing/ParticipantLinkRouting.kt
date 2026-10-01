package com.competra.data.routing

import com.competra.data.requests.orienteering.CreateParticipantLinkRequest
import com.competra.data.requests.orienteering.ReviewParticipantLinkRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.ParticipantLinkRequestService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Заявки на привязку вручную внесённых участников к аккаунтам. Только под JWT. */
fun Route.participantLinkRoutes(service: ParticipantLinkRequestService) {
    get("/event/orienteering/link-requests/suggestions") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = service.suggestions(userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    post("/event/orienteering/link-requests") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val request = call.receive<CreateParticipantLinkRequest>()
        val result = service.create(userId, request.participantIds, request.source)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/event/orienteering/link-requests/mine") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = service.listMine(userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/event/orienteering/link-requests/competition") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val competitionId = call.request.queryParameters["competitionId"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )
        val result = service.listForCompetition(competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/event/orienteering/link-requests/pending-counts") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = service.pendingCounts(userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    put("/event/orienteering/link-requests/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val requestId = call.parameters["id"]!!
        val request = call.receive<ReviewParticipantLinkRequest>()
        val result = service.review(requestId, userId, request.approve, request.comment)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/event/orienteering/link-requests/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val requestId = call.parameters["id"]!!
        service.cancel(requestId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    post("/event/orienteering/participants/{id}/unlink") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val participantId = call.parameters["id"]!!
        service.unlink(participantId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }
}
