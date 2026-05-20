package com.competra.data.scheduler

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.services.FcmService
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

private const val INTERVAL_MS = 60_000L * 10        // 10 минута — скан окна старта
private const val WINDOW_LOWER_MS = 50 * 60_000L    // не раньше чем за 60 мин
private const val WINDOW_UPPER_MS = 60 * 60_000L    // и не позже чем за 50 мин до старта

/**
 * Раз в [INTERVAL_MS] миллисекунд ищет соревнования, до старта которых осталось
 * 50–60 минут и которым ещё не отправляли уведомление, и пушит зарегистрированным
 * участникам сообщение «Старт через час».
 */
fun Application.configureStartNotificationScheduler(fcmService: FcmService) {
    val handler = CoroutineExceptionHandler { _, t ->
        log.error("StartNotificationScheduler exception", t)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    scope.launch {
        log.info("StartNotificationScheduler started, interval=${INTERVAL_MS}ms")
        while (isActive) {
            runCatching { runTick(fcmService) }
                .onFailure { log.error("StartNotificationScheduler tick failed", it) }
            delay(INTERVAL_MS)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("StartNotificationScheduler stopping")
        scope.cancel()
    }
}

private suspend fun runTick(fcmService: FcmService) {
    val now = System.currentTimeMillis()
    val from = now + WINDOW_LOWER_MS
    val to = now + WINDOW_UPPER_MS

    val competitionsDue = newSuspendedTransaction(Dispatchers.IO) {
        Competitions.selectAll()
            .where {
                (Competitions.startDate greaterEq from) and
                    (Competitions.startDate lessEq to) and
                    (Competitions.startNotificationSent eq false)
            }
            .map { it[Competitions.id] to it[Competitions.title] }
    }

    if (competitionsDue.isEmpty()) return

    for ((competitionId, title) in competitionsDue) {
        val userIds = newSuspendedTransaction(Dispatchers.IO) {
            OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.competitionId eq competitionId }
                .mapNotNull { it[OrienteeringParticipants.userId] }
                .distinct()
        }
        userIds.forEach { userId ->
            fcmService.sendToUser(
                userId = userId,
                title = "Старт через час",
                body = "Соревнование «$title» начнётся через час",
                data = mapOf(
                    "competition_id" to competitionId.toString(),
                    "kind" to "competition_start_reminder",
                ),
            )
        }
        newSuspendedTransaction(Dispatchers.IO) {
            Competitions.update({ Competitions.id eq competitionId }) {
                it[startNotificationSent] = true
            }
        }
    }
}
