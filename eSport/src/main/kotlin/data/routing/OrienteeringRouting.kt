package com.competra.data.routing

import com.competra.data.requests.orienteering.DistanceRequest
import com.competra.data.requests.orienteering.OrienteeringCompetitionRequest
import com.competra.data.util.IOFXmlParser
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.toByteArray
import com.competra.data.requests.orienteering.OrienteeringParticipantRequest
import com.competra.data.requests.orienteering.OrienteeringResultRequest
import com.competra.data.requests.orienteering.ParticipantGroupRequest
import com.competra.data.requests.orienteering.RegisterParticipantRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.DistanceService
import com.competra.data.services.OrienteeringCompetitionService
import com.competra.data.services.OrienteeringParticipantService
import com.competra.data.services.OrienteeringResultService
import com.competra.data.services.ParticipantGroupService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.DateTimeException
import java.time.ZoneId

// ConflictException теперь ловится глобально в StatusPages.kt c идентичным ответом
// (HTTP 409 + CommonModel { status=0, result=currentResponse, errors=[BaseError(409, "Server record is newer")] }).

fun Route.orienteeringPublicRoutes(
    competitionService: OrienteeringCompetitionService,
    participantService: OrienteeringParticipantService,
    resultService: OrienteeringResultService,
    groupService: ParticipantGroupService
) {
    get("/event/orienteering/competitions/public") {
        val kindOfSports = call.request.queryParameters.getAll("kind_of_sports") ?: emptyList()
        val statuses = call.request.queryParameters.getAll("statuses") ?: emptyList()
        val dateFrom = call.request.queryParameters["date_from"]?.toLongOrNull()
        val dateTo = call.request.queryParameters["date_to"]?.toLongOrNull()
        val includeTest = call.request.queryParameters["includeTest"]?.toBoolean() ?: false
        call.application.log.info(
            "PUBLIC_COMPETITIONS filter — kindOfSports=$kindOfSports, statuses=$statuses, dateFrom=$dateFrom, dateTo=$dateTo, includeTest=$includeTest, fullUri=${call.request.uri}"
        )
        val list = competitionService.getPublicCompetitions(
            kindOfSports = kindOfSports,
            statuses = statuses,
            dateFrom = dateFrom,
            dateTo = dateTo,
            includeTest = includeTest
        )
        call.application.log.info(
            "PUBLIC_COMPETITIONS result — returned ${list.size} items, statuses in result: ${list.map { it.status }.distinct()}"
        )
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

    get("/event/orienteering/results/competition") {
        val competitionId = call.request.queryParameters["competitionId"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )
        val results = resultService.getByCompetition(competitionId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = results
        })
    }

    get("/event/orienteering/participantGroups") {
        val competitionId = call.request.queryParameters["competitionId"]
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
        val competitionId = call.request.queryParameters["competitionId"]
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

    get("/event/orienteering/competitions/public/{id}") {
        val id = call.parameters["id"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val userId = call.request.queryParameters["userId"]
        val detail = competitionService.getByIdOrLegacy(id, userId)
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

        try {
            ZoneId.of(req.competition.timeZoneId)
        } catch (e: DateTimeException) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also {
                    it.status = 0
                    it.errors = listOf(BaseError(400, "Invalid timeZoneId: ${req.competition.timeZoneId}"))
                }
            )
        }

        val result = competitionService.upsert(req, userId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/event/orienteering/competitions") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@get call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val list = competitionService.getByUserId(userId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = list
        })
    }

    get("/event/orienteering/competitions/registered") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@get call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val list = competitionService.getRegisteredByUserId(userId)
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

    post("/event/orienteering/save/results") {
        val requests = call.receive<List<OrienteeringResultRequest>>()
        val result = resultService.upsertAll(requests)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    delete("/event/orienteering/competitions/{id}") {
        val id = call.parameters["id"]
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val deleted = competitionService.deleteById(id)
        call.respond(CommonModel<Any>().also { it.status = if (deleted) 1 else 0 })
    }

    delete("/event/orienteering/participantGroups/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val deleted = groupService.deleteById(id)
        call.respond(CommonModel<Any>().also { it.status = if (deleted) 1 else 0 })
    }

    delete("/event/orienteering/participants/{id}") {
        val id = call.parameters["id"]
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val deleted = participantService.deleteById(id)
        call.respond(CommonModel<Any>().also { it.status = if (deleted) 1 else 0 })
    }

    delete("/event/orienteering/results/{id}") {
        val id = call.parameters["id"]
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val deleted = resultService.deleteById(id)
        call.respond(CommonModel<Any>().also { it.status = if (deleted) 1 else 0 })
    }

    delete("/event/orienteering/distances/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val deleted = distanceService.deleteById(id)
        call.respond(CommonModel<Any>().also { it.status = if (deleted) 1 else 0 })
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

    post("/event/orienteering/import/courses") {
        val multipart = call.receiveMultipart()
        var xmlBytes: ByteArray? = null
        var competitionId: String? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> xmlBytes = part.provider().toByteArray()
                is PartData.FormItem -> if (part.name == "competitionId") competitionId = part.value
                else -> {}
            }
            part.dispose()
        }

        if (xmlBytes == null || competitionId == null)
            return@post call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also {
                    it.status = 0
                    it.errors = listOf(BaseError(400, "xmlFile and competitionId are required"))
                }
            )

        val requests = IOFXmlParser.parse(xmlBytes!!, competitionId!!)
        val result = distanceService.upsertAll(requests)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/event/orienteering/distances") {
        val competitionId = call.request.queryParameters["competitionId"]
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

    delete("/event/orienteering/register/{competitionId}") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@delete call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )

        val competitionId = call.parameters["competitionId"]
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "competitionId is required")) }
            )

        participantService.cancelRegistration(competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }
}
