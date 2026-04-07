package com.sportenth.data.routing

import com.sportenth.data.requests.orienteering.OrienteeringCompetitionRequest
import com.sportenth.data.requests.orienteering.OrienteeringParticipantRequest
import com.sportenth.data.requests.orienteering.OrienteeringResultRequest
import com.sportenth.data.requests.orienteering.ParticipantGroupRequest
import com.sportenth.data.response.base.BaseError
import com.sportenth.data.response.base.CommonModel
import com.sportenth.data.services.OrienteeringCompetitionService
import com.sportenth.data.services.OrienteeringParticipantService
import com.sportenth.data.services.OrienteeringResultService
import com.sportenth.data.services.ParticipantGroupService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.orienteeringRoutes(
    competitionService: OrienteeringCompetitionService,
    groupService: ParticipantGroupService,
    participantService: OrienteeringParticipantService,
    resultService: OrienteeringResultService
) {
    post("/event/orienteering/save/competitions") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val req = call.receive<OrienteeringCompetitionRequest>()
        val result = competitionService.upsert(req, userId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/event/orienteering/competitions") {
        val userId = call.parameters["userId"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "userId is required")) }
            )

        val list = competitionService.getByUserId(userId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = list
        })
    }

    get("/event/orienteering/competitions/public") {
        val kindOfSports = call.request.queryParameters.getAll("kind_of_sports") ?: emptyList()
        val list = competitionService.getByKindOfSports(kindOfSports)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = list
        })
    }

    post("/event/orienteering/save/participantGroup") {
        val requests = call.receive<List<ParticipantGroupRequest>>()
        val result = groupService.upsertAll(requests)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    post("/event/orienteering/save/participant") {
        val req = call.receive<OrienteeringParticipantRequest>()
        val result = participantService.upsert(req)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    post("/event/orienteering/save/result") {
        val req = call.receive<OrienteeringResultRequest>()
        val result = resultService.upsert(req)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }
}
