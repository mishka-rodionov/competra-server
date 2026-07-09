package com.competra.data.routing

import com.competra.data.requests.clubs.AddTeamMemberRequest
import com.competra.data.requests.clubs.ChangeTeamMemberRoleRequest
import com.competra.data.requests.clubs.CreateTeamRequest
import com.competra.data.requests.clubs.UpdateTeamRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.TeamMemberService
import com.competra.data.services.TeamService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.teamsPublicRoutes(teamService: TeamService, teamMemberService: TeamMemberService) {
    get("/clubs/{clubId}/teams") {
        val clubId = call.parameters["clubId"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "clubId is required")) }
            )
        val result = teamService.listByClub(clubId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/teams/{id}") {
        val id = call.parameters["id"]!!
        val result = teamService.getById(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/teams/{id}/members") {
        val id = call.parameters["id"]!!
        val result = teamMemberService.listMembers(id)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}

fun Route.teamsRoutes(teamService: TeamService, teamMemberService: TeamMemberService) {
    post("/clubs/{clubId}/teams") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val clubId = call.parameters["clubId"]!!
        val request = call.receive<CreateTeamRequest>()
        val result = teamService.create(clubId, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    put("/teams/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val request = call.receive<UpdateTeamRequest>()
        val result = teamService.update(id, request, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/teams/{id}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        teamService.delete(id, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    post("/teams/{id}/members") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val request = call.receive<AddTeamMemberRequest>()
        val result = teamMemberService.addMember(id, request.clubMemberId, request.role, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    delete("/teams/{id}/members/{clubMemberId}") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val clubMemberId = call.parameters["clubMemberId"]!!
        teamMemberService.removeMember(id, clubMemberId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }

    put("/teams/{id}/members/{clubMemberId}/role") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val id = call.parameters["id"]!!
        val clubMemberId = call.parameters["clubMemberId"]!!
        val request = call.receive<ChangeTeamMemberRoleRequest>()
        val result = teamMemberService.changeRole(id, clubMemberId, request.role, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}
