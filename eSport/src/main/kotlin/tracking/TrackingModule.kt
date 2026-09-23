package com.competra.tracking

import com.competra.configureAuthentication
import com.competra.configureCors
import com.competra.configureMonitoring
import com.competra.configureSerialization
import com.competra.configureStatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.TransactionManager

/**
 * Процесс онлайн-трекинга (`APP_MODE=tracking`): отдельный контейнер из того же образа.
 *
 * Ставит только то, что нужно трекингу: CORS, JWT, логирование, сериализацию, обработку ошибок и
 * свои пулы к БД. Шедулеры, RabbitMQ-демо, S3/SMTP/FCM основного приложения здесь не запускаются.
 * Сбой этого процесса затрагивает только `/api/live-track/…` — nginx отдаёт на них 502, основное
 * приложение продолжает принимать финиш и результаты.
 */
fun Application.trackingModule() {
    configureCors()
    configureAuthentication()
    configureMonitoring()
    configureSerialization()
    configureStatusPages()

    val databases = connectTrackingDatabases()
    // Транзакции без явной БД должны уходить в БД трекинга, а не в основную.
    TransactionManager.defaultDatabase = databases.tracking

    routing {
        // Liveness для Docker/отладки внутри сети; снаружи не публикуется.
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP", "service" to "tracking"))
        }
        route("/api/live-track") {
            trackingHealthRoutes(databases)
        }
    }
}

/**
 * `GET /api/live-track/health` — готовность трекинга, доступная снаружи через nginx.
 * 200, если отвечает собственная БД трекинга (без неё трекинг не работает); состояние основной
 * БД показывается отдельно — без неё не стартуют новые сессии, но активные продолжают писать.
 */
private fun Route.trackingHealthRoutes(databases: TrackingDatabases) {
    get("/health") {
        // Обе БД проверяются параллельно: ответ не дольше одного connectionTimeout пула.
        val (trackingDbUp, mainDbUp) = coroutineScope {
            val tracking = async { databases.trackingDataSource.isReachable() }
            val main = async { databases.mainReadOnlyDataSource.isReachable() }
            tracking.await() to main.await()
        }
        call.respond(
            if (trackingDbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            mapOf(
                "status" to if (trackingDbUp) "UP" else "DOWN",
                "trackingDb" to if (trackingDbUp) "UP" else "DOWN",
                "mainDb" to if (mainDbUp) "UP" else "DOWN"
            )
        )
    }
}

/**
 * Проверка БД напрямую через JDBC, без Exposed: `newSuspendedTransaction` при недоступном пуле
 * падает фатальной ошибкой корутин вместо обычного исключения. Время ограничено
 * `connectionTimeout` пула и таймаутом [java.sql.Connection.isValid].
 */
private suspend fun HikariDataSource.isReachable(): Boolean = withContext(Dispatchers.IO) {
    runCatching { connection.use { it.isValid(HEALTH_VALIDATION_TIMEOUT_SEC) } }.getOrDefault(false)
}

/** Таймаут проверки уже полученного соединения. */
private const val HEALTH_VALIDATION_TIMEOUT_SEC = 2
