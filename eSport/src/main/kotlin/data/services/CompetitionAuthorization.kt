package com.competra.data.services

import com.competra.data.database.entity.CompetitionOrganizers
import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.exception.ForbiddenException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Права, которыми может обладать организатор соревнования (роль в [CompetitionOrganizers]).
 * Owner соревнования и FOUNDER/ADMIN клуба-организатора имеют все права всегда,
 * независимо от того, есть ли для них строка в [CompetitionOrganizers].
 */
enum class CompetitionPermission {
    /** Редактировать поля самого соревнования, удалять его. */
    MANAGE_COMPETITION,
    /** Добавлять/удалять/менять роли организаторов. Даётся только через владение/MAIN — см. [ROLE_PERMISSIONS]. */
    MANAGE_ORGANIZERS,
    MANAGE_DISTANCES,
    MANAGE_GROUPS,
    MANAGE_PARTICIPANTS,
    MANAGE_RESULTS
}

/** MAIN | JUDGE | SECRETARY | COURSE_SETTER | OTHER — значения зеркалят Android OrganizerRole. */
private val ROLE_PERMISSIONS: Map<String, Set<CompetitionPermission>> = mapOf(
    "MAIN" to CompetitionPermission.entries.toSet(),
    "JUDGE" to setOf(CompetitionPermission.MANAGE_PARTICIPANTS, CompetitionPermission.MANAGE_RESULTS),
    "SECRETARY" to setOf(CompetitionPermission.MANAGE_PARTICIPANTS, CompetitionPermission.MANAGE_GROUPS),
    "COURSE_SETTER" to setOf(CompetitionPermission.MANAGE_DISTANCES),
    "OTHER" to emptySet()
)

/**
 * Бросает [ForbiddenException], если userId не может выполнить [permission] над соревнованием
 * competitionId. Владелец соревнования и FOUNDER/ADMIN клуба-организатора проходят всегда;
 * иначе смотрим роль в [CompetitionOrganizers] и её права по [ROLE_PERMISSIONS].
 * Должна вызываться внутри уже открытой транзакции Exposed (dbQuery/newSuspendedTransaction).
 */
fun requireCompetitionPermission(competitionId: String, userId: String, permission: CompetitionPermission) {
    val comp = Competitions.selectAll()
        .where { Competitions.id eq competitionId }
        .singleOrNull() ?: throw ForbiddenException("Соревнование не найдено")

    if (comp[Competitions.ownerId] == userId) return

    val clubId = comp[Competitions.organizingClubId]
    val clubRole = clubId?.let {
        ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq it) and (ClubMembers.userId eq userId) }
            .singleOrNull()
            ?.get(ClubMembers.role)
    }
    if (clubRole in listOf("FOUNDER", "ADMIN")) return

    val organizerRole = CompetitionOrganizers.selectAll()
        .where { (CompetitionOrganizers.competitionId eq competitionId) and (CompetitionOrganizers.userId eq userId) }
        .singleOrNull()
        ?.get(CompetitionOrganizers.role)

    if (organizerRole == null || permission !in (ROLE_PERMISSIONS[organizerRole] ?: emptySet())) {
        throw ForbiddenException("Недостаточно прав для этого действия над соревнованием")
    }
}

/** Полное редактирование соревнования (поля, удаление) — только owner/MAIN/club-admin. */
fun requireCompetitionEditAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_COMPETITION)

/** Управление списком организаторов — только owner/MAIN/club-admin (иначе — эскалация прав). */
fun requireOrganizerManageAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_ORGANIZERS)

fun requireDistanceEditAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_DISTANCES)

fun requireGroupEditAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_GROUPS)

fun requireParticipantEditAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_PARTICIPANTS)

fun requireResultEditAccess(competitionId: String, userId: String) =
    requireCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_RESULTS)

private val CLUB_ADMIN_ROLES = listOf("FOUNDER", "ADMIN")

/** Роли организаторов, которым [ROLE_PERMISSIONS] даёт [permission]. */
private fun rolesWith(permission: CompetitionPermission): List<String> =
    ROLE_PERMISSIONS.filterValues { permission in it }.keys.toList()

/** Вариант [requireCompetitionPermission] без исключения. Внутри открытой транзакции. */
fun hasCompetitionPermission(competitionId: String, userId: String, permission: CompetitionPermission): Boolean =
    try {
        requireCompetitionPermission(competitionId, userId, permission)
        true
    } catch (e: ForbiddenException) {
        false
    }

/**
 * Зарегистрированные пользователи, которые могут выполнить [permission] над соревнованием:
 * владелец, FOUNDER/ADMIN клуба-организатора и организаторы с подходящей ролью.
 * Внутри открытой транзакции.
 */
fun usersWithCompetitionPermission(competitionId: String, permission: CompetitionPermission): Set<String> {
    val comp = Competitions.selectAll()
        .where { Competitions.id eq competitionId }
        .singleOrNull() ?: return emptySet()
    val users = mutableSetOf<String>()
    comp[Competitions.ownerId]?.takeIf { it.isNotBlank() }?.let(users::add)
    comp[Competitions.organizingClubId]?.let { clubId ->
        ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.role inList CLUB_ADMIN_ROLES) }
            .mapTo(users) { it[ClubMembers.userId] }
    }
    CompetitionOrganizers.selectAll()
        .where {
            (CompetitionOrganizers.competitionId eq competitionId) and
                (CompetitionOrganizers.role inList rolesWith(permission))
        }
        .mapNotNullTo(users) { it[CompetitionOrganizers.userId]?.takeIf { id -> id.isNotBlank() } }
    return users
}

/** Соревнования, над которыми [userId] может выполнить [permission]. Внутри открытой транзакции. */
fun competitionIdsWithPermission(userId: String, permission: CompetitionPermission): Set<String> {
    val ids = mutableSetOf<String>()
    Competitions.select(Competitions.id)
        .where { Competitions.ownerId eq userId }
        .mapTo(ids) { it[Competitions.id] }
    val adminClubIds = ClubMembers.selectAll()
        .where { (ClubMembers.userId eq userId) and (ClubMembers.role inList CLUB_ADMIN_ROLES) }
        .map { it[ClubMembers.clubId] }
    if (adminClubIds.isNotEmpty()) {
        Competitions.select(Competitions.id)
            .where { Competitions.organizingClubId inList adminClubIds }
            .mapTo(ids) { it[Competitions.id] }
    }
    CompetitionOrganizers.select(CompetitionOrganizers.competitionId)
        .where { (CompetitionOrganizers.userId eq userId) and (CompetitionOrganizers.role inList rolesWith(permission)) }
        .mapTo(ids) { it[CompetitionOrganizers.competitionId] }
    return ids
}
