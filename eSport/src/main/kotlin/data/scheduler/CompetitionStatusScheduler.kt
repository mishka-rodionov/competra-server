package com.competra.data.scheduler

import com.competra.CompetitionNotificationLogServiceKey
import com.competra.FcmServiceKey
import com.competra.data.services.OrienteeringCompetitionService
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val INTERVAL_MS = 5 * 60_000L  // 5 минут

/**
 * Регистрирует фоновую корутину, которая раз в 5 минут пересчитывает
 * [com.competra.data.database.entity.Competitions.status] для всех соревнований,
 * у которых наступило новое временное состояние (окно регистрации открылось/закрылось,
 * соревнование стартовало).
 *
 * Read-path в сервисах продолжает использовать `computeEffectiveStatus()`
 * как fallback между тиками — клиенты всегда получают актуальный статус, даже
 * если БД ещё не синхронизирована.
 */
fun Application.configureStatusScheduler() {
    val service = OrienteeringCompetitionService(attributes[FcmServiceKey], attributes[CompetitionNotificationLogServiceKey])
    val handler = CoroutineExceptionHandler { _, t ->
        log.error("CompetitionStatusScheduler exception", t)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)

    scope.launch {
        log.info("CompetitionStatusScheduler started, interval=${INTERVAL_MS}ms")
        while (isActive) {
            runCatching { service.recalculateAllStatuses() }
                .onSuccess { count ->
                    if (count > 0) log.info("CompetitionStatusScheduler updated $count competitions")
                }
                .onFailure { log.error("CompetitionStatusScheduler tick failed", it) }
            delay(INTERVAL_MS)
        }
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("CompetitionStatusScheduler stopping")
        scope.cancel()
    }
}
