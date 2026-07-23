package com.competra.data.scheduler

import com.competra.data.database.entity.CompetitionNotificationType
import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.services.CompetitionNotificationLogService
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

private const val INTERVAL_MS = 60_000L * 10 // 10 минут — интервал скана окон

/** Окно поиска соревнований для одного вида напоминания. */
internal data class ReminderWindow(
    val type: CompetitionNotificationType,
    val kind: String,
    val windowLowerMs: Long,
    val windowUpperMs: Long,
    val title: String,
    val body: (competitionTitle: String) -> String,
)

internal val REMINDER_WINDOWS = listOf(
    ReminderWindow(
        type = CompetitionNotificationType.START_REMINDER,
        kind = "competition_start_reminder",
        windowLowerMs = 50 * 60_000L, // не раньше чем за 60 мин
        windowUpperMs = 60 * 60_000L, // и не позже чем за 50 мин до старта
        title = "Старт через час",
        body = { title -> "Соревнование «$title» начнётся через час" },
    ),
    ReminderWindow(
        type = CompetitionNotificationType.DAY_BEFORE_REMINDER,
        kind = "day_before_reminder",
        windowLowerMs = (23 * 60 + 50) * 60_000L, // не раньше чем за 24ч
        windowUpperMs = 24 * 60 * 60_000L,        // и не позже чем за 23ч50м до старта
        title = "Завтра старт!",
        body = { title -> "Соревнование «$title» стартует через сутки" },
    ),
)

/**
 * Раз в [INTERVAL_MS] миллисекунд, для каждого окна из [REMINDER_WINDOWS], ищет соревнования,
 * до старта которых остался соответствующий промежуток времени и которым ещё не отправляли
 * уведомление этого типа (см. [CompetitionNotificationLogService]), и пушит зарегистрированным
 * участникам напоминание.
 */
fun Application.configureReminderNotificationScheduler(
    fcmService: FcmService,
    notificationLogService: CompetitionNotificationLogService,
) {
    val handler = CoroutineExceptionHandler { _, t ->
        log.error("ReminderNotificationScheduler exception", t)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    scope.launch {
        log.info("ReminderNotificationScheduler started, interval=${INTERVAL_MS}ms")
        while (isActive) {
            REMINDER_WINDOWS.forEach { window ->
                runCatching { runTick(window, fcmService, notificationLogService) }
                    .onFailure { log.error("ReminderNotificationScheduler tick failed for ${window.type}", it) }
            }
            delay(INTERVAL_MS)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("ReminderNotificationScheduler stopping")
        scope.cancel()
    }
}

/** Диапазон startDate, попадающий в окно напоминания относительно текущего момента [now]. Чистая функция — покрыта unit-тестом. */
internal fun reminderWindowBounds(now: Long, window: ReminderWindow): LongRange =
    (now + window.windowLowerMs)..(now + window.windowUpperMs)

private suspend fun runTick(
    window: ReminderWindow,
    fcmService: FcmService,
    notificationLogService: CompetitionNotificationLogService,
) {
    val now = System.currentTimeMillis()
    val bounds = reminderWindowBounds(now, window)

    val competitionsInWindow = newSuspendedTransaction(Dispatchers.IO) {
        Competitions.selectAll()
            .where { (Competitions.startDate greaterEq bounds.first) and (Competitions.startDate lessEq bounds.last) }
            .map { it[Competitions.id] to it[Competitions.title] }
    }
    if (competitionsInWindow.isEmpty()) return

    val unsentIds = notificationLogService.unsentCompetitionIds(window.type, competitionsInWindow.map { it.first })
    if (unsentIds.isEmpty()) return

    val titleById = competitionsInWindow.toMap()
    for (competitionId in unsentIds) {
        val competitionTitle = titleById.getValue(competitionId)
        val userIds = newSuspendedTransaction(Dispatchers.IO) {
            OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.competitionId eq competitionId }
                .mapNotNull { it[OrienteeringParticipants.userId] }
                .distinct()
        }
        userIds.forEach { userId ->
            fcmService.sendToUser(
                userId = userId,
                title = window.title,
                body = window.body(competitionTitle),
                data = mapOf("competition_id" to competitionId, "kind" to window.kind),
                includeNotificationBlock = false,
            )
        }
        notificationLogService.markSent(competitionId, window.type)
    }
}
