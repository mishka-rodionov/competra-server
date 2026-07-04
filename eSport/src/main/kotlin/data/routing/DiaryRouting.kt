package com.competra.data.routing

import com.competra.data.requests.diary.WorkoutRequest
import com.competra.data.response.base.BaseError
import com.competra.data.response.base.CommonModel
import com.competra.data.services.DiaryWorkoutService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Роуты тренировочного дневника. Вызывается из уже аутентифицированного блока
 * (см. `Routing.kt` — рядом с `orienteeringRoutes`/`deviceRoutes`). В отличие от
 * orienteering — без server-wins/409: тренировки всегда однопользовательские,
 * upsert просто перезаписывает запись владельца.
 */
fun Route.diaryRoutes(diaryWorkoutService: DiaryWorkoutService) {
    post("/diary/workouts") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )
        val requests = call.receive<List<WorkoutRequest>>()
        val result = diaryWorkoutService.upsertAll(userId, requests)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    get("/diary/workouts") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@get call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )
        val result = diaryWorkoutService.getByUser(userId)
        call.respond(CommonModel<Any>().also { model ->
            model.status = 1
            model.result = result
        })
    }

    delete("/diary/workouts/{id}") {
        val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
            ?: return@delete call.respond(
                HttpStatusCode.Unauthorized,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(401, "Unauthorized")) }
            )
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                CommonModel<Any>().also { it.status = 0; it.errors = listOf(BaseError(400, "id is required")) }
            )
        diaryWorkoutService.deleteById(id, userId)
        call.respond(CommonModel<Any>().also { it.status = 1 })
    }
}
