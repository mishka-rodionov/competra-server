package com.competra.data.services

import com.competra.UserService
import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.exception.ConflictException
import com.competra.data.exception.ForbiddenException
import com.competra.data.exception.UnprocessableEntityException
import com.competra.data.requests.orienteering.OrienteeringParticipantRequest
import com.competra.data.requests.orienteering.RegisterParticipantRequest
import com.competra.data.response.orienteering.OrienteeringParticipantResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class OrienteeringParticipantService {

    private fun computeEffectiveStatus(
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

    suspend fun upsertAll(requests: List<OrienteeringParticipantRequest>, callerUserId: String): List<OrienteeringParticipantResponse> = dbQuery {
        val now = System.currentTimeMillis()
        requests.map { req ->
            val existing = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq req.id }
                .singleOrNull()

            val serverTs = existing?.get(OrienteeringParticipants.updatedAt) ?: 0L
            if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
                req.serverUpdatedAt < serverTs
            ) {
                throw ConflictException(existing.toResponse(), req.serverUpdatedAt, serverTs)
            }

            if (existing == null) {
                requireParticipantEditAccess(req.competitionId, callerUserId)
                OrienteeringParticipants.insert {
                    it[id] = req.id
                    it[userId] = req.userId
                    it[firstName] = req.firstName
                    it[lastName] = req.lastName
                    it[groupId] = req.groupId
                    it[groupName] = req.groupName
                    it[competitionId] = req.competitionId
                    it[commandName] = req.commandName
                    it[startNumber] = req.startNumber
                    it[startTime] = req.startTime
                    it[chipNumber] = req.chipNumber
                    it[comment] = req.comment
                    it[isChipGiven] = req.isChipGiven
                    it[updatedAt] = now
                }
            } else {
                if (existing[OrienteeringParticipants.competitionId] != req.competitionId) {
                    throw ForbiddenException("Участник принадлежит другому соревнованию")
                }
                requireParticipantEditAccess(req.competitionId, callerUserId)
                OrienteeringParticipants.update({ OrienteeringParticipants.id eq req.id }) {
                    it[userId] = req.userId
                    it[firstName] = req.firstName
                    it[lastName] = req.lastName
                    it[groupId] = req.groupId
                    it[groupName] = req.groupName
                    it[competitionId] = req.competitionId
                    it[commandName] = req.commandName
                    it[startNumber] = req.startNumber
                    it[startTime] = req.startTime
                    it[chipNumber] = req.chipNumber
                    it[comment] = req.comment
                    it[isChipGiven] = req.isChipGiven
                    it[updatedAt] = now
                }
            }

            OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq req.id }
                .single()
                .toResponse()
        }
    }

    suspend fun upsert(req: OrienteeringParticipantRequest, callerUserId: String): OrienteeringParticipantResponse = dbQuery {
        val now = System.currentTimeMillis()
        val existing = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq req.id }
            .singleOrNull()

        val serverTs = existing?.get(OrienteeringParticipants.updatedAt) ?: 0L
        if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
            req.serverUpdatedAt < serverTs
        ) {
            throw ConflictException(existing.toResponse(), req.serverUpdatedAt, serverTs)
        }

        if (existing == null) {
            requireParticipantEditAccess(req.competitionId, callerUserId)
            OrienteeringParticipants.insert {
                it[id] = req.id
                it[userId] = req.userId
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[groupId] = req.groupId
                it[groupName] = req.groupName
                it[competitionId] = req.competitionId
                it[commandName] = req.commandName
                it[startNumber] = req.startNumber
                it[startTime] = req.startTime
                it[chipNumber] = req.chipNumber
                it[comment] = req.comment
                it[isChipGiven] = req.isChipGiven
                it[updatedAt] = now
            }
        } else {
            if (existing[OrienteeringParticipants.competitionId] != req.competitionId) {
                throw ForbiddenException("Участник принадлежит другому соревнованию")
            }
            requireParticipantEditAccess(req.competitionId, callerUserId)
            OrienteeringParticipants.update({ OrienteeringParticipants.id eq req.id }) {
                it[userId] = req.userId
                it[firstName] = req.firstName
                it[lastName] = req.lastName
                it[groupId] = req.groupId
                it[groupName] = req.groupName
                it[competitionId] = req.competitionId
                it[commandName] = req.commandName
                it[startNumber] = req.startNumber
                it[startTime] = req.startTime
                it[chipNumber] = req.chipNumber
                it[comment] = req.comment
                it[isChipGiven] = req.isChipGiven
                it[updatedAt] = now
            }
        }

        OrienteeringParticipants.selectAll().where { OrienteeringParticipants.id eq req.id }.single().toResponse()
    }

    /**
     * Регистрирует пользователя как участника соревнования.
     * Если пользователь уже зарегистрирован — выбрасывает IllegalStateException.
     * Если группа не из этого соревнования, в ней нет мест или она не подходит пользователю
     * по полу/возрасту (см. [checkGroupEligibility]) — UnprocessableEntityException (HTTP 422).
     */
    suspend fun register(req: RegisterParticipantRequest, userId: String): OrienteeringParticipantResponse = dbQuery {
        // Проверяем статус соревнования — регистрация доступна только при REGISTRATION_OPEN
        val comp = Competitions.selectAll()
            .where { Competitions.id eq req.competitionId }
            .singleOrNull() ?: throw IllegalStateException("Соревнование не найдено")

        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq req.competitionId }
            .singleOrNull()

        val effectiveStatus = computeEffectiveStatus(
            storedStatus = comp[Competitions.status],
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            startTime = orient?.get(OrienteeringCompetitions.startTime)
        )
        if (effectiveStatus != "REGISTRATION_OPEN") {
            throw IllegalStateException("Регистрация на данное соревнование недоступна")
        }

        // Проверяем, не зарегистрирован ли уже пользователь на это соревнование
        val alreadyRegistered = OrienteeringParticipants.selectAll()
            .where {
                (OrienteeringParticipants.userId eq userId) and
                (OrienteeringParticipants.competitionId eq req.competitionId)
            }
            .singleOrNull()

        if (alreadyRegistered != null) {
            throw IllegalStateException("Вы уже зарегистрированы на данный старт")
        }

        val group = ParticipantGroups.selectAll()
            .where { (ParticipantGroups.id eq req.groupId) and (ParticipantGroups.competitionId eq req.competitionId) }
            .singleOrNull()
            ?: throw UnprocessableEntityException("Группа не найдена в этом соревновании")
        val groupName = group[ParticipantGroups.title]

        group[ParticipantGroups.maxParticipants]?.takeIf { it > 0 }?.let { limit ->
            val registeredCount = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.groupId eq req.groupId }
                .count()
            if (registeredCount >= limit) throw UnprocessableEntityException("В группе $groupName не осталось мест")
        }

        // Только самостоятельная регистрация: организатор через upsertAll ставит участника в любую группу.
        val user = UserService.Users.selectAll()
            .where { UserService.Users.id eq userId }
            .singleOrNull()
        checkGroupEligibility(
            groupTitle = groupName,
            groupGender = group[ParticipantGroups.gender],
            minAge = group[ParticipantGroups.minAge],
            maxAge = group[ParticipantGroups.maxAge],
            userGender = user?.get(UserService.Users.gender),
            userBirthDate = user?.get(UserService.Users.birthDate),
            competitionYear = competitionYear(comp[Competitions.startDate], comp[Competitions.timeZoneId]),
        )?.let { throw UnprocessableEntityException(it) }

        val participantId = UUID.randomUUID().toString()
        OrienteeringParticipants.insert {
            it[id] = participantId
            it[OrienteeringParticipants.userId] = userId
            it[firstName] = req.firstName
            it[lastName] = req.lastName
            it[groupId] = req.groupId
            it[OrienteeringParticipants.groupName] = groupName
            it[competitionId] = req.competitionId
            it[commandName] = req.commandName
            it[startNumber] = 0
            it[startTime] = 0L
            it[chipNumber] = 0L
            it[comment] = null
            it[isChipGiven] = false
            it[updatedAt] = System.currentTimeMillis()
        }

        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq participantId }
            .single()
            .toResponse()
    }

    /**
     * Отменяет регистрацию пользователя на соревнование.
     * Отмена доступна только пока регистрация не закрыта (статус CREATED или REGISTRATION_OPEN):
     * после окончания регистрации — в т.ч. досрочного, когда организатор кнопкой «Завершить
     * регистрацию» выставляет registrationEnd на текущий момент — стартовый протокол меняет только
     * организатор. Иначе удаление участника на сервере конфликтует с его офлайн-правками.
     * Если регистрация закрыта — выбрасывает IllegalStateException.
     */
    suspend fun cancelRegistration(competitionId: String, userId: String) = dbQuery {
        val comp = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull() ?: throw IllegalStateException("Соревнование не найдено")

        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()

        val effectiveStatus = computeEffectiveStatus(
            storedStatus = comp[Competitions.status],
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            startTime = orient?.get(OrienteeringCompetitions.startTime)
        )
        if (effectiveStatus != "CREATED" && effectiveStatus != "REGISTRATION_OPEN") {
            throw IllegalStateException("Регистрация завершена, отменить её уже нельзя")
        }

        OrienteeringParticipants.deleteWhere {
            (OrienteeringParticipants.userId eq userId) and
            (OrienteeringParticipants.competitionId eq competitionId)
        }
    }

    /**
     * Проверяет, зарегистрирован ли пользователь на данное соревнование.
     */
    suspend fun isRegistered(competitionId: String, userId: String): Boolean = dbQuery {
        OrienteeringParticipants.selectAll()
            .where {
                (OrienteeringParticipants.userId eq userId) and
                (OrienteeringParticipants.competitionId eq competitionId)
            }
            .singleOrNull() != null
    }

    suspend fun getByCompetition(competitionId: String): List<OrienteeringParticipantResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.competitionId eq competitionId }
            .map { row -> row.toResponse() }
    }

    suspend fun getByGroup(groupId: Long): List<OrienteeringParticipantResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.groupId eq groupId }
            .map { it.toResponse() }
    }

    /**
     * Удаляет участника (действие организатора).
     * После старта соревнования удаление запрещено: к участнику уже привязаны стартовое время,
     * чип и результат, а удаление сдвигает места в протоколе. Неявку в этом случае отмечают
     * результатом со статусом DNS.
     *
     * @throws UnprocessableEntityException если соревнование уже стартовало (HTTP 422).
     */
    suspend fun deleteById(id: String, callerUserId: String): Boolean = dbQuery {
        val existing = OrienteeringParticipants.selectAll().where { OrienteeringParticipants.id eq id }.singleOrNull()
            ?: return@dbQuery false
        val competitionId = existing[OrienteeringParticipants.competitionId]
        requireParticipantEditAccess(competitionId, callerUserId)
        if (isCompetitionStarted(competitionId)) {
            throw UnprocessableEntityException(
                "Соревнование уже стартовало — удалить участника нельзя, отметьте его как «Не стартовал»"
            )
        }
        @Suppress("DEPRECATION")
        OrienteeringParticipants.deleteWhere { OrienteeringParticipants.id eq id } > 0
    }

    /**
     * true, если соревнование уже стартовало: сохранённый статус IN_PROGRESS/FINISHED/ARCHIVED
     * либо наступило время старта (фоновый шедулер переводит статус с задержкой до 5 минут).
     */
    private fun isCompetitionStarted(competitionId: String): Boolean {
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

    private fun ResultRow.toResponse() = OrienteeringParticipantResponse(
        id = this[OrienteeringParticipants.id],
        userId = this[OrienteeringParticipants.userId],
        firstName = this[OrienteeringParticipants.firstName],
        lastName = this[OrienteeringParticipants.lastName],
        groupId = this[OrienteeringParticipants.groupId],
        groupName = this[OrienteeringParticipants.groupName],
        competitionId = this[OrienteeringParticipants.competitionId],
        commandName = this[OrienteeringParticipants.commandName],
        startNumber = this[OrienteeringParticipants.startNumber],
        startTime = this[OrienteeringParticipants.startTime],
        chipNumber = this[OrienteeringParticipants.chipNumber],
        comment = this[OrienteeringParticipants.comment],
        isChipGiven = this[OrienteeringParticipants.isChipGiven],
        updatedAt = this[OrienteeringParticipants.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    companion object {
        /** Статусы, после которых участника нельзя удалить. */
        private val STARTED_STATUSES = setOf("IN_PROGRESS", "FINISHED", "ARCHIVED")
    }
}
