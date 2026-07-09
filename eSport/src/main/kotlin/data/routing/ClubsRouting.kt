package com.competra.data.routing

import com.competra.data.requests.clubs.ChangeRoleRequest
import com.competra.data.requests.clubs.CreateClubRequest
import com.competra.data.requests.clubs.ReviewJoinRequestRequest
import com.competra.data.requests.clubs.UpdateClubRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.ClubJoinRequestService
import com.competra.data.services.ClubMemberService
import com.competra.data.services.ClubService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

fun Route.clubsPublicRoutes(clubService: ClubService, clubMemberService: ClubMemberService) {
    get("/clubs") {
        val query = call.request.queryParameters["query"]
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE)
            ?: DEFAULT_PAGE_SIZE
        val result = clubService.search(query, page, limit)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/{id}") {
        val id = call.parameters["id"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val result = clubService.getById(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/{id}/members") {
        val id = call.parameters["id"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val result = clubMemberService.listMembers(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}

fun Route.clubsRoutes(
    clubService: ClubService,
    clubMemberService: ClubMemberService,
    clubJoinRequestService: ClubJoinRequestService
) {
    post("/clubs") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val request = call.receive<CreateClubRequest>()
        val result = clubService.create(request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/mine") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = clubService.listMine(userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    put("/clubs/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val request = call.receive<UpdateClubRequest>()
        val result = clubService.update(id, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/clubs/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        clubService.delete(id, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    delete("/clubs/{id}/members/{userId}") {
        val requesterUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["id"]!!
        val targetUserId = call.parameters["userId"]!!
        clubMemberService.removeMember(clubId, requesterUserId, targetUserId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    put("/clubs/{id}/members/{userId}/role") {
        val requesterUserId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["id"]!!
        val targetUserId = call.parameters["userId"]!!
        val request = call.receive<ChangeRoleRequest>()
        val result = clubMemberService.changeRole(clubId, requesterUserId, targetUserId, request.role)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    post("/clubs/{id}/join-requests") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["id"]!!
        val result = clubJoinRequestService.create(clubId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/{id}/join-requests") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["id"]!!
        val result = clubJoinRequestService.listForClub(clubId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/join-requests/mine") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = clubJoinRequestService.listMine(userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    put("/clubs/{id}/join-requests/{requestId}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["id"]!!
        val requestId = call.parameters["requestId"]!!
        val request = call.receive<ReviewJoinRequestRequest>()
        val result = clubJoinRequestService.review(clubId, requestId, userId, request.approve)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}
