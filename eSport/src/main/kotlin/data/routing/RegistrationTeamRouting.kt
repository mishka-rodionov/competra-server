package com.competra.data.routing

import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.RegistrationTeamService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Подсказки для поля «Команда» при регистрации на соревнование. Только для авторизованных. */
fun Route.registrationTeamRoutes(registrationTeamService: RegistrationTeamService) {
    get("/event/orienteering/competitions/{id}/registration-team-options") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val competitionId = call.parameters["id"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        val result = registrationTeamService.getOptions(competitionId, userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }

    get("/clubs/match") {
        val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
        val result = registrationTeamService.matchClubs(call.request.queryParameters["name"], userId)
        call.respond(CommonModel<Any>().also { it.status = 1; it.result = result })
    }
}
