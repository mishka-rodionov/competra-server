package com.sportenth

import com.sportenth.data.routing.orienteeringRoutes
import com.sportenth.data.services.OrienteeringCompetitionService
import com.sportenth.data.services.OrienteeringParticipantService
import com.sportenth.data.services.OrienteeringResultService
import com.sportenth.data.services.ParticipantGroupService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    install(AutoHeadResponse)

    val competitionService = OrienteeringCompetitionService()
    val groupService = ParticipantGroupService()
    val participantService = OrienteeringParticipantService()
    val resultService = OrienteeringResultService()

    routing {
        get("/info") {
            call.respondText("Hello World!")
        }

        authenticate("auth-jwt") {
            orienteeringRoutes(competitionService, groupService, participantService, resultService)
        }
    }
}
