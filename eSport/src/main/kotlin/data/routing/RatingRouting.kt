package com.competra.data.routing

import com.competra.data.requests.rating.AddCompetitionToRatingRequest
import com.competra.data.requests.rating.CreateRatingRequest
import com.competra.data.requests.rating.SetGroupMappingRequest
import com.competra.data.requests.rating.UpdateRatingRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.RatingService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

fun Route.ratingPublicRoutes(ratingService: RatingService) {
    get("/ratings") {
        val query = call.request.queryParameters["query"]
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE)
            ?: DEFAULT_PAGE_SIZE
        val result = ratingService.search(query, page, limit)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/{clubId}/ratings") {
        val clubId = call.parameters["clubId"]!!
        val result = ratingService.listForClub(clubId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/ratings/{id}") {
        val id = call.parameters["id"]!!
        val result = ratingService.getById(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/ratings/{id}/competitions") {
        val id = call.parameters["id"]!!
        val result = ratingService.listCompetitions(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/ratings/{id}/competitions/{competitionId}/mapping-suggestions") {
        val id = call.parameters["id"]!!
        val competitionId = call.parameters["competitionId"]!!
        val result = ratingService.getGroupMappingSuggestions(id, competitionId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/ratings/{id}/standings") {
        val id = call.parameters["id"]!!
        val groupId = call.request.queryParameters["groupId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "groupId is required")) }
            )
        val result = ratingService.getStandings(id, groupId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}

fun Route.ratingRoutes(ratingService: RatingService) {
    post("/clubs/{clubId}/ratings") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["clubId"]!!
        val request = call.receive<CreateRatingRequest>()
        val result = ratingService.create(clubId, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    put("/ratings/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val request = call.receive<UpdateRatingRequest>()
        val result = ratingService.update(id, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/ratings/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        ratingService.delete(id, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    post("/ratings/{id}/competitions") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val request = call.receive<AddCompetitionToRatingRequest>()
        val result = ratingService.addCompetition(id, request.competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/ratings/{id}/competitions/{competitionId}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val competitionId = call.parameters["competitionId"]!!
        ratingService.removeCompetition(id, competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    put("/ratings/{id}/competitions/{competitionId}/mapping") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val competitionId = call.parameters["competitionId"]!!
        val request = call.receive<SetGroupMappingRequest>()
        ratingService.setGroupMapping(id, competitionId, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }
}
