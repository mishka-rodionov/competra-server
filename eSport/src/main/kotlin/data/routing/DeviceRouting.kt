package com.competra.data.routing

import com.competra.data.requests.FcmTokenRequest
import com.competra.data.response.base.CommonModel
import com.competra.data.services.DeviceTokenService
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.deviceRoutes(deviceTokenService: DeviceTokenService) {
    route("/devices") {
        post("/fcm-token") {
            val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asString()
            val request = call.receive<FcmTokenRequest>()
            deviceTokenService.upsert(userId, request)
            call.respond(CommonModel<Unit>().also { it.status = 1 })
        }

        delete("/fcm-token") {
            val token = call.request.queryParameters["token"]
                ?: call.receive<FcmTokenRequest>().token
            deviceTokenService.delete(token)
            call.respond(CommonModel<Unit>().also { it.status = 1 })
        }
    }
}
