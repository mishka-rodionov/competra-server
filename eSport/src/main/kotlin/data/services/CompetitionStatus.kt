package com.competra.data.services

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringCompetitions
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Статусы, начиная с которых соревнование считается стартовавшим. */
val STARTED_STATUSES = setOf("IN_PROGRESS", "FINISHED", "ARCHIVED")

/**
 * Статус соревнования с учётом дат: сохранённый в БД статус обновляет фоновый шедулер с задержкой
 * до 5 минут, поэтому read-path пересчитывает его по registrationStart/registrationEnd/startTime.
 * IN_PROGRESS и FINISHED выставляются вручную и возвращаются как есть.
 */
fun computeEffectiveStatus(
    storedStatus: String,
    registrationStart: Long?,
    registrationEnd: Long?,
    startTime: Long?
): String {
    if (storedStatus == "IN_PROGRESS" || storedStatus == "FINISHED") return storedStatus
    val now = System.currentTimeMillis()
    return when {
        startTime != null && now >= startTime -> "IN_PROGRESS"
        registrationEnd != null && now >= registrationEnd -> "REGISTRATION_CLOSED"
        registrationStart == null || now >= registrationStart -> "REGISTRATION_OPEN"
        else -> "CREATED"
    }
}

/**
 * true, если соревнование уже стартовало: сохранённый статус IN_PROGRESS/FINISHED/ARCHIVED
 * либо наступило время старта (фоновый шедулер переводит статус с задержкой до 5 минут).
 * Черновик (DRAFT) не стартует никогда. Внутри открытой транзакции.
 */
fun isCompetitionStarted(competitionId: String): Boolean {
    val comp = Competitions.selectAll()
        .where { Competitions.id eq competitionId }
        .singleOrNull() ?: return false
    val storedStatus = comp[Competitions.status]
    if (storedStatus in STARTED_STATUSES) return true
    val orient = OrienteeringCompetitions.selectAll()
        .where { OrienteeringCompetitions.id eq competitionId }
        .singleOrNull()
    val effectiveStatus = computeEffectiveStatus(
        storedStatus = storedStatus,
        registrationStart = comp[Competitions.registrationStart],
        registrationEnd = comp[Competitions.registrationEnd],
        startTime = orient?.get(OrienteeringCompetitions.startTime)
    )
    return storedStatus != "DRAFT" && effectiveStatus in STARTED_STATUSES
}
