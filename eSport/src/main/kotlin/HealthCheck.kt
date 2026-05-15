package com.competra

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.healthRoutes() {
    // Liveness: процесс жив. Внешний пингер (UptimeRobot, Better Stack) должен бить сюда.
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    }

    // Readiness: приложение готово обслуживать запросы — БД отвечает.
    get("/health/ready") {
        val dbOk = runCatching {
            withContext(Dispatchers.IO) {
                transaction {
                    exec("SELECT 1")
                }
            }
        }.isSuccess

        val code = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(
            code,
            mapOf(
                "status" to if (dbOk) "UP" else "DOWN",
                "db" to if (dbOk) "UP" else "DOWN"
            )
        )
    }
}
