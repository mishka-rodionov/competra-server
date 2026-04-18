package com.sportenth.data.routing

import com.sportenth.data.requests.orienteering.DistanceRequest
import com.sportenth.data.requests.orienteering.OrienteeringCompetitionRequest
import com.sportenth.data.requests.orienteering.OrienteeringParticipantRequest
import com.sportenth.data.requests.orienteering.OrienteeringResultRequest
import com.sportenth.data.requests.orienteering.ParticipantGroupRequest
import com.sportenth.data.requests.orienteering.RegisterParticipantRequest
import com.sportenth.data.response.base.BaseError
import com.sportenth.data.response.base.CommonModel
import com.sportenth.data.services.DistanceService
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

fun Route.orienteeringPublicRoutes(
    competitionService: OrienteeringCompetitionService,
    participantService: OrienteeringParticipantService
) {
    get("/event/orienteering/competitions/public") {
        val kindOfSports = call.request.queryParameters.getAll("kind_of_sports") ?: emptyList()
        val list = competitionService.getByKindOfSports(kindOfSports)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = list
        })
    }

    get("/event/orienteering/participants") {
        val groupId = call.request.queryParameters["groupId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "groupId is required")) }
            )
        val participants = participantService.getByGroup(groupId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = participants
        })
    }

    get("/event/orienteering/competitions/public/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val userId = call.request.queryParameters["userId"]
        val detail = competitionService.getById(id, userId)
            ?: return@get call.respond(
                HttpStatusCode.NotFound,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(404, "Competition not found")) }
            )
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = detail
        })
    }
}

fun Route.orienteeringRoutes(
    competitionService: OrienteeringCompetitionService,
    groupService: ParticipantGroupService,
    participantService: OrienteeringParticipantService,
    resultService: OrienteeringResultService,
    distanceService: DistanceService
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

    post("/event/orienteering/save/participants") {
        val requests = call.receive<List<OrienteeringParticipantRequest>>()
        val result = participantService.upsertAll(requests)
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

    post("/event/orienteering/register") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val req = call.receive<RegisterParticipantRequest>()
        try {
            val result = participantService.register(req, userId)
            call.respond(CommonModel<Any>().also { model ->
                model.status = 1
                model.result = result
            })
        } catch (e: IllegalStateException) {
            call.respond(
                HttpStatusCode.Conflict,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(409, e.message ?: "Already registered")) }
            )
        }
    }

    post("/event/orienteering/save/distances") {
        val requests = call.receive<List<DistanceRequest>>()
        val result = distanceService.upsertAll(requests)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/event/orienteering/distances") {
        val competitionId = call.request.queryParameters["competitionId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )
        val result = distanceService.getByCompetition(competitionId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/event/orienteering/participantGroups") {
        val competitionId = call.request.queryParameters["competitionId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )
        val result = groupService.getByCompetition(competitionId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/event/orienteering/participants/competition") {
        val competitionId = call.request.queryParameters["competitionId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )
        val result = participantService.getByCompetition(competitionId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    delete("/event/orienteering/register/{competitionId}") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@delete call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val competitionId = call.parameters["competitionId"]?.toLongOrNull()
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )

        participantService.cancelRegistration(competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }
}
