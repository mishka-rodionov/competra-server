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
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    val service = LiveTrackService(
        repository = ExposedLiveTrackRepository(databases.tracking),
        participants = MainDbParticipantDirectory(databases.mainReadOnly),
        store = LiveTrackStore()
    )
    launchBackgroundJobs(service)

    routing {
        // Liveness для Docker/отладки внутри сети; снаружи не публикуется.
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP", "service" to "tracking"))
        }
        route("/api/live-track") {
            trackingHealthRoutes(databases, service)
            liveTrackRoutes(service)
        }
    }
}

/**
 * Фоновые задачи трекинга в своём scope — их сбой не роняет процесс:
 * - инициализация схемы и памяти с повторами (БД могла быть недоступна на старте);
 * - сторож закрытия просроченных сессий раз в [SWEEP_INTERVAL_MS];
 * - подписка на `result.saved` в RabbitMQ (если задан `RABBITMQ_URI`).
 */
private fun Application.launchBackgroundJobs(service: LiveTrackService) {
    val handler = CoroutineExceptionHandler { _, t -> log.error("Tracking background job failed", t) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    scope.launch {
        while (isActive && !service.isReady()) {
            runCatching { service.initialize() }
                .onSuccess { log.info("Live tracking initialized") }
                .onFailure {
                    log.warn("Live tracking init failed (${it.message}), retry in ${INIT_RETRY_MS}ms")
                    delay(INIT_RETRY_MS)
                }
        }
        while (isActive) {
            delay(SWEEP_INTERVAL_MS)
            runCatching { service.sweep() }
                .onSuccess { closed -> if (closed > 0) log.info("Live tracking sweep closed $closed session(s)") }
                .onFailure { log.warn("Live tracking sweep failed", it) }
        }
    }

    val consumer = System.getenv("RABBITMQ_URI")?.takeIf { it.isNotBlank() }
        ?.let { ResultSavedConsumer(it, service, log) }
    if (consumer != null) {
        consumer.start(scope)
    } else {
        log.warn("RABBITMQ_URI is not set: sessions will close only manually or by timeout")
    }

    monitor.subscribe(ApplicationStopping) {
        consumer?.stop()
        scope.cancel()
    }
}

/** Пауза между попытками инициализации, пока БД трекинга недоступна. */
private const val INIT_RETRY_MS = 10_000L

/** Период сторожа сессий. */
private const val SWEEP_INTERVAL_MS = 60_000L

/**
 * `GET /api/live-track/health` — готовность трекинга, доступная снаружи через nginx.
 * 200, если отвечает собственная БД трекинга (без неё трекинг не работает); состояние основной
 * БД показывается отдельно — без неё не стартуют новые сессии, но активные продолжают писать.
 */
private fun Route.trackingHealthRoutes(databases: TrackingDatabases, service: LiveTrackService) {
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
                "mainDb" to if (mainDbUp) "UP" else "DOWN",
                "initialized" to service.isReady().toString()
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
