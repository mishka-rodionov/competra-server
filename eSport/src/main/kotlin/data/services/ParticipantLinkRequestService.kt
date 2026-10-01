package com.competra.data.services

import com.competra.UserService
import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.database.entity.ParticipantLinkRequests
import com.competra.data.exception.ForbiddenException
import com.competra.data.exception.UnprocessableEntityException
import com.competra.data.response.orienteering.CompetitionLinkRequestResponse
import com.competra.data.response.orienteering.LinkRequestResponse
import com.competra.data.response.orienteering.LinkResultSummary
import com.competra.data.response.orienteering.LinkSuggestionResponse
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Заявки «этот участник протокола — я»: пользователь находит результаты, которые организатор внёс
 * вручную (участник без userId), организатор проверяет и одобряет — в участника записывается userId.
 * Результаты, сплиты и треки висят на участнике, поэтому ничего не копируется.
 *
 * «Непривязанный» — userId null ИЛИ пустая строка: Android пишет "" для вручную добавленных участников.
 */
class ParticipantLinkRequestService(private val fcmService: FcmService) {

    private val log = LoggerFactory.getLogger(ParticipantLinkRequestService::class.java)

    suspend fun suggestions(userId: String): List<LinkSuggestionResponse> = dbQuery {
        val user = UserService.Users.selectAll()
            .where { UserService.Users.id eq userId }
            .singleOrNull() ?: return@dbQuery emptyList()
        val firstName = user[UserService.Users.firstName]
        val lastName = user[UserService.Users.lastName]
        val middleName = user[UserService.Users.middleName]

        val myCompetitionIds = OrienteeringParticipants.select(OrienteeringParticipants.competitionId)
            .where { OrienteeringParticipants.userId eq userId }
            .mapTo(mutableSetOf()) { it[OrienteeringParticipants.competitionId] }
        // PENDING — уже подана, REJECTED — организатор сказал «не вы», больше не предлагаем (вручную подать можно).
        val excludedParticipantIds = ParticipantLinkRequests.select(ParticipantLinkRequests.participantId)
            .where {
                (ParticipantLinkRequests.userId eq userId) and
                    (ParticipantLinkRequests.status inList listOf(STATUS_PENDING, STATUS_REJECTED))
            }
            .mapTo(mutableSetOf()) { it[ParticipantLinkRequests.participantId] }

        // Имена сравниваем в Kotlin, а не в SQL: LOWER() для кириллицы зависит от локали БД, плюс ё/е и
        // перестановка фамилии/имени. Читаем только id и имена непривязанных — на текущих объёмах это дёшево.
        val matchedIds = OrienteeringParticipants
            .select(
                OrienteeringParticipants.id,
                OrienteeringParticipants.firstName,
                OrienteeringParticipants.lastName,
                OrienteeringParticipants.competitionId,
            )
            .where { isUnlinked() }
            .filter {
                it[OrienteeringParticipants.competitionId] !in myCompetitionIds &&
                    it[OrienteeringParticipants.id] !in excludedParticipantIds &&
                    namesMatch(
                        firstName, lastName, middleName,
                        it[OrienteeringParticipants.firstName], it[OrienteeringParticipants.lastName],
                    )
            }
            .map { it[OrienteeringParticipants.id] }
        if (matchedIds.isEmpty()) return@dbQuery emptyList()

        val rows = participantsWithCompetition()
            .where { OrienteeringParticipants.id inList matchedIds }
            .orderBy(Competitions.startDate, SortOrder.DESC)
            .limit(MAX_SUGGESTIONS)
            .toList()
        val results = resultSummaries(rows.map { it[OrienteeringParticipants.id] })
        rows.map { row ->
            val participantId = row[OrienteeringParticipants.id]
            LinkSuggestionResponse(
                participantId = participantId,
                competitionId = row[Competitions.id],
                competitionTitle = row[Competitions.title],
                competitionStartDate = row[Competitions.startDate],
                firstName = row[OrienteeringParticipants.firstName],
                lastName = row[OrienteeringParticipants.lastName],
                groupName = row[OrienteeringParticipants.groupName],
                commandName = row[OrienteeringParticipants.commandName],
                result = results[participantId],
            )
        }
    }

    suspend fun create(userId: String, participantIds: List<String>, source: String): List<LinkRequestResponse> {
        if (source !in SOURCES) throw UnprocessableEntityException("Неизвестный источник заявки: $source")
        if (participantIds.isEmpty()) throw UnprocessableEntityException("Не выбраны результаты")

        val (requestIds, notices) = dbQuery {
            val now = System.currentTimeMillis()
            val requestIds = mutableListOf<String>()
            val notices = mutableListOf<Notice>()
            var pendingCount = ParticipantLinkRequests.selectAll()
                .where { (ParticipantLinkRequests.userId eq userId) and (ParticipantLinkRequests.status eq STATUS_PENDING) }
                .count()

            for (participantId in participantIds.distinct()) {
                val participant = OrienteeringParticipants.selectAll()
                    .where { OrienteeringParticipants.id eq participantId }
                    .singleOrNull() ?: throw NotFoundException("Участник не найден")
                val competitionId = participant[OrienteeringParticipants.competitionId]
                val participantName = participant.fullName()
                val linkedUserId = participant[OrienteeringParticipants.userId]?.takeIf { it.isNotBlank() }
                if (linkedUserId == userId) continue
                if (linkedUserId != null) {
                    throw UnprocessableEntityException("Результат «$participantName» уже привязан к другому пользователю")
                }
                if (userParticipantIn(competitionId, userId, exceptParticipantId = null) != null) {
                    throw UnprocessableEntityException(
                        "Вы уже есть в протоколе соревнования «${competitionTitle(competitionId)}». " +
                            "Если это дубль, попросите организатора удалить лишнюю запись"
                    )
                }

                val pendingInCompetition = ParticipantLinkRequests.selectAll()
                    .where {
                        (ParticipantLinkRequests.userId eq userId) and
                            (ParticipantLinkRequests.competitionId eq competitionId) and
                            (ParticipantLinkRequests.status eq STATUS_PENDING)
                    }
                    .singleOrNull()
                if (pendingInCompetition != null) {
                    if (pendingInCompetition[ParticipantLinkRequests.participantId] == participantId) {
                        requestIds += pendingInCompetition[ParticipantLinkRequests.id]
                        continue
                    }
                    throw UnprocessableEntityException(
                        "Заявка на результат в соревновании «${competitionTitle(competitionId)}» уже подана"
                    )
                }
                if (pendingCount >= MAX_PENDING_PER_USER) {
                    throw UnprocessableEntityException(
                        "Слишком много заявок на рассмотрении. Дождитесь решения организаторов"
                    )
                }

                val requestId = UUID.randomUUID().toString()
                ParticipantLinkRequests.insert {
                    it[id] = requestId
                    it[ParticipantLinkRequests.participantId] = participantId
                    it[ParticipantLinkRequests.competitionId] = competitionId
                    it[ParticipantLinkRequests.userId] = userId
                    it[status] = STATUS_PENDING
                    it[requestSource] = source
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                requestIds += requestId

                // Организатор привязывает собственный результат — проверять некому, кроме него самого.
                if (hasCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_PARTICIPANTS)) {
                    notices += approveLocked(requestId, reviewerId = userId, now = now)
                } else {
                    pendingCount++
                    val applicant = userName(userId)
                    val title = competitionTitle(competitionId)
                    usersWithCompetitionPermission(competitionId, CompetitionPermission.MANAGE_PARTICIPANTS)
                        .filter { it != userId }
                        .forEach { organizerId ->
                            notices += Notice(
                                userId = organizerId,
                                title = "Заявка на привязку результата",
                                body = "$applicant просит привязать результат «$participantName» — «$title»",
                                data = mapOf("competition_id" to competitionId, "kind" to KIND_REQUESTED),
                            )
                        }
                }
            }
            requestIds to notices
        }

        // Один пуш организатору на соревнование, даже если заявлено несколько участников.
        notices.distinctBy { it.userId to it.data["competition_id"] }.forEach { send(it) }
        return dbQuery { requestIds.map { myRequestResponse(it) } }
    }

    suspend fun listMine(userId: String): List<LinkRequestResponse> = dbQuery {
        myRequestsQuery()
            .where { ParticipantLinkRequests.userId eq userId }
            .orderBy(ParticipantLinkRequests.createdAt, SortOrder.DESC)
            .map { it.toMyResponse() }
    }

    suspend fun cancel(requestId: String, userId: String) = dbQuery {
        val request = ParticipantLinkRequests.selectAll()
            .where { ParticipantLinkRequests.id eq requestId }
            .singleOrNull() ?: throw NotFoundException("Заявка не найдена")
        if (request[ParticipantLinkRequests.userId] != userId) throw ForbiddenException("Это не ваша заявка")
        if (request[ParticipantLinkRequests.status] != STATUS_PENDING) {
            throw UnprocessableEntityException("Заявка уже обработана")
        }
        ParticipantLinkRequests.update({ ParticipantLinkRequests.id eq requestId }) {
            it[status] = STATUS_CANCELLED
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    suspend fun listForCompetition(competitionId: String, requesterId: String): List<CompetitionLinkRequestResponse> =
        dbQuery {
            requireParticipantEditAccess(competitionId, requesterId)
            val competition = Competitions.selectAll()
                .where { Competitions.id eq competitionId }
                .single()
            val year = competitionYear(competition[Competitions.startDate], competition[Competitions.timeZoneId])
            val groups = ParticipantGroups.selectAll()
                .where { ParticipantGroups.competitionId eq competitionId }
                .associateBy { it[ParticipantGroups.id] }
            // userId → id участников соревнования, привязанных к нему
            val participantIdsByUser = OrienteeringParticipants
                .select(OrienteeringParticipants.id, OrienteeringParticipants.userId)
                .where { OrienteeringParticipants.competitionId eq competitionId }
                .mapNotNull { row ->
                    row[OrienteeringParticipants.userId]?.takeIf { it.isNotBlank() }
                        ?.let { it to row[OrienteeringParticipants.id] }
                }
                .groupBy({ it.first }, { it.second })

            val rows = ParticipantLinkRequests
                .join(
                    OrienteeringParticipants, JoinType.INNER,
                    ParticipantLinkRequests.participantId, OrienteeringParticipants.id,
                )
                .join(UserService.Users, JoinType.INNER, ParticipantLinkRequests.userId, UserService.Users.id)
                .selectAll()
                .where { ParticipantLinkRequests.competitionId eq competitionId }
                .orderBy(ParticipantLinkRequests.createdAt, SortOrder.DESC)
                .toList()
            val pendingByParticipant = rows
                .filter { it[ParticipantLinkRequests.status] == STATUS_PENDING }
                .groupingBy { it[ParticipantLinkRequests.participantId] }
                .eachCount()
            val results = resultSummaries(rows.map { it[OrienteeringParticipants.id] }.distinct())

            rows.map { row ->
                val participantId = row[OrienteeringParticipants.id]
                val status = row[ParticipantLinkRequests.status]
                val claimantId = row[ParticipantLinkRequests.userId]
                val userGender = row[UserService.Users.gender]
                val userBirthDate = row[UserService.Users.birthDate]
                val group = groups[row[OrienteeringParticipants.groupId]]
                val eligibilityWarning = group?.let {
                    linkEligibilityWarning(
                        groupTitle = it[ParticipantGroups.title],
                        groupGender = it[ParticipantGroups.gender],
                        minAge = it[ParticipantGroups.minAge],
                        maxAge = it[ParticipantGroups.maxAge],
                        userGender = userGender,
                        userBirthDate = userBirthDate,
                        competitionYear = year,
                    )
                }
                val ownPending = if (status == STATUS_PENDING) 1 else 0
                CompetitionLinkRequestResponse(
                    id = row[ParticipantLinkRequests.id],
                    status = status,
                    source = row[ParticipantLinkRequests.requestSource],
                    comment = row[ParticipantLinkRequests.comment],
                    createdAt = row[ParticipantLinkRequests.createdAt],
                    participantId = participantId,
                    participantFirstName = row[OrienteeringParticipants.firstName],
                    participantLastName = row[OrienteeringParticipants.lastName],
                    groupName = row[OrienteeringParticipants.groupName],
                    commandName = row[OrienteeringParticipants.commandName],
                    startNumber = row[OrienteeringParticipants.startNumber],
                    result = results[participantId],
                    userId = claimantId,
                    userFirstName = row[UserService.Users.firstName],
                    userLastName = row[UserService.Users.lastName],
                    userBirthYear = parseBirthYear(userBirthDate),
                    userGender = userGender,
                    nameMatches = namesMatch(
                        row[UserService.Users.firstName],
                        row[UserService.Users.lastName],
                        row[UserService.Users.middleName],
                        row[OrienteeringParticipants.firstName],
                        row[OrienteeringParticipants.lastName],
                    ),
                    eligibilityWarning = eligibilityWarning,
                    competingRequests = (pendingByParticipant[participantId] ?: 0) - ownPending,
                    userAlreadyInCompetition = participantIdsByUser[claimantId].orEmpty().any { it != participantId },
                )
            }
        }

    /** competitionId → число PENDING-заявок, по соревнованиям, где пользователь может управлять участниками. */
    suspend fun pendingCounts(requesterId: String): Map<String, Int> = dbQuery {
        val competitionIds = competitionIdsWithPermission(requesterId, CompetitionPermission.MANAGE_PARTICIPANTS)
        if (competitionIds.isEmpty()) return@dbQuery emptyMap()
        val countColumn = ParticipantLinkRequests.id.count()
        ParticipantLinkRequests.select(ParticipantLinkRequests.competitionId, countColumn)
            .where {
                (ParticipantLinkRequests.status eq STATUS_PENDING) and
                    (ParticipantLinkRequests.competitionId inList competitionIds)
            }
            .groupBy(ParticipantLinkRequests.competitionId)
            .associate { it[ParticipantLinkRequests.competitionId] to it[countColumn].toInt() }
    }

    suspend fun review(requestId: String, reviewerId: String, approve: Boolean, comment: String?): LinkRequestResponse {
        val notices = dbQuery {
            val request = ParticipantLinkRequests.selectAll()
                .where { ParticipantLinkRequests.id eq requestId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Заявка не найдена")
            requireParticipantEditAccess(request[ParticipantLinkRequests.competitionId], reviewerId)
            if (request[ParticipantLinkRequests.status] != STATUS_PENDING) {
                throw UnprocessableEntityException("Заявка уже обработана")
            }
            val now = System.currentTimeMillis()
            if (approve) {
                approveLocked(requestId, reviewerId, now)
            } else {
                val reason = comment?.trim()?.take(COMMENT_MAX_LENGTH)?.ifBlank { null }
                ParticipantLinkRequests.update({ ParticipantLinkRequests.id eq requestId }) {
                    it[status] = STATUS_REJECTED
                    it[ParticipantLinkRequests.comment] = reason
                    it[reviewedBy] = reviewerId
                    it[reviewedAt] = now
                    it[updatedAt] = now
                }
                val competitionId = request[ParticipantLinkRequests.competitionId]
                listOf(
                    rejectedNotice(request[ParticipantLinkRequests.userId], competitionId, reason)
                )
            }
        }
        notices.forEach { send(it) }
        return dbQuery { myRequestResponse(requestId) }
    }

    /** Отвязать участника: сам привязанный пользователь или тот, кто может управлять участниками. */
    suspend fun unlink(participantId: String, requesterId: String) = dbQuery {
        val participant = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq participantId }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("Участник не найден")
        val linkedUserId = participant[OrienteeringParticipants.userId]?.takeIf { it.isNotBlank() }
            ?: return@dbQuery
        if (linkedUserId != requesterId) {
            requireParticipantEditAccess(participant[OrienteeringParticipants.competitionId], requesterId)
        }
        val now = System.currentTimeMillis()
        OrienteeringParticipants.update({ OrienteeringParticipants.id eq participantId }) {
            it[userId] = null
            it[updatedAt] = now
        }
        // Иначе в истории заявок так и висело бы «Привязан».
        ParticipantLinkRequests.update({
            (ParticipantLinkRequests.participantId eq participantId) and
                (ParticipantLinkRequests.userId eq linkedUserId) and
                (ParticipantLinkRequests.status eq STATUS_APPROVED)
        }) {
            it[status] = STATUS_UNLINKED
            it[updatedAt] = now
        }
    }

    /**
     * Одобряет PENDING-заявку: пишет userId в участника и отклоняет конкурирующие заявки на него же.
     * Строка участника блокируется (FOR UPDATE), чтобы два одновременных одобрения не прошли оба.
     * Возвращает уведомления для рассылки после коммита.
     */
    private fun approveLocked(requestId: String, reviewerId: String, now: Long): List<Notice> {
        val request = ParticipantLinkRequests.selectAll()
            .where { ParticipantLinkRequests.id eq requestId }
            .single()
        val participantId = request[ParticipantLinkRequests.participantId]
        val competitionId = request[ParticipantLinkRequests.competitionId]
        val claimantId = request[ParticipantLinkRequests.userId]

        val participant = OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.id eq participantId }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("Участник не найден")
        val linkedUserId = participant[OrienteeringParticipants.userId]?.takeIf { it.isNotBlank() }
        if (linkedUserId != null && linkedUserId != claimantId) {
            throw UnprocessableEntityException("Результат уже привязан к другому пользователю")
        }
        if (linkedUserId == null) {
            if (userParticipantIn(competitionId, claimantId, exceptParticipantId = participantId) != null) {
                throw UnprocessableEntityException(
                    "У заявителя уже есть участник в этом соревновании. Удалите дубль, затем одобрите заявку"
                )
            }
            OrienteeringParticipants.update({ OrienteeringParticipants.id eq participantId }) {
                it[userId] = claimantId
                it[updatedAt] = now
            }
        }
        ParticipantLinkRequests.update({ ParticipantLinkRequests.id eq requestId }) {
            it[status] = STATUS_APPROVED
            it[reviewedBy] = reviewerId
            it[reviewedAt] = now
            it[updatedAt] = now
        }

        val competing = ParticipantLinkRequests.selectAll()
            .where {
                (ParticipantLinkRequests.participantId eq participantId) and
                    (ParticipantLinkRequests.status eq STATUS_PENDING) and
                    (ParticipantLinkRequests.id neq requestId)
            }
            .map { it[ParticipantLinkRequests.userId] }
        if (competing.isNotEmpty()) {
            ParticipantLinkRequests.update({
                (ParticipantLinkRequests.participantId eq participantId) and
                    (ParticipantLinkRequests.status eq STATUS_PENDING)
            }) {
                it[status] = STATUS_REJECTED
                it[comment] = AUTO_REJECT_COMMENT
                it[reviewedBy] = reviewerId
                it[reviewedAt] = now
                it[updatedAt] = now
            }
        }

        val notices = mutableListOf<Notice>()
        // Себе (автоодобрение организатором собственного результата) пуш не шлём.
        if (claimantId != reviewerId) {
            notices += Notice(
                userId = claimantId,
                title = "Результат привязан к профилю",
                body = "Организатор подтвердил ваш результат — «${competitionTitle(competitionId)}»",
                data = mapOf("competition_id" to competitionId, "kind" to KIND_REVIEWED, "approved" to "true"),
            )
        }
        competing.forEach { notices += rejectedNotice(it, competitionId, AUTO_REJECT_COMMENT) }
        return notices
    }

    private fun rejectedNotice(userId: String, competitionId: String, reason: String?) = Notice(
        userId = userId,
        title = "Заявка на привязку отклонена",
        body = "«${competitionTitle(competitionId)}»" + (reason?.let { ": $it" } ?: ""),
        data = mapOf("competition_id" to competitionId, "kind" to KIND_REVIEWED, "approved" to "false"),
    )

    private suspend fun send(notice: Notice) {
        runCatching {
            fcmService.sendToUser(
                userId = notice.userId,
                title = notice.title,
                body = notice.body,
                data = notice.data,
            )
        }.onFailure { log.warn("Failed to send link request push to userId=${notice.userId}", it) }
    }

    private fun isUnlinked(): Op<Boolean> =
        OrienteeringParticipants.userId.isNull() or (OrienteeringParticipants.userId eq "")

    /** Участник соревнования, уже привязанный к [userId] (кроме [exceptParticipantId]). */
    private fun userParticipantIn(competitionId: String, userId: String, exceptParticipantId: String?): ResultRow? =
        OrienteeringParticipants.selectAll()
            .where { (OrienteeringParticipants.competitionId eq competitionId) and (OrienteeringParticipants.userId eq userId) }
            .firstOrNull { it[OrienteeringParticipants.id] != exceptParticipantId }

    private fun competitionTitle(competitionId: String): String =
        Competitions.select(Competitions.title)
            .where { Competitions.id eq competitionId }
            .singleOrNull()?.get(Competitions.title) ?: "Соревнование"

    private fun userName(userId: String): String =
        UserService.Users.selectAll()
            .where { UserService.Users.id eq userId }
            .singleOrNull()
            ?.let { "${it[UserService.Users.lastName]} ${it[UserService.Users.firstName]}".trim() }
            ?.ifBlank { null } ?: "Пользователь"

    private fun ResultRow.fullName(): String =
        "${this[OrienteeringParticipants.lastName]} ${this[OrienteeringParticipants.firstName]}".trim()

    private fun resultSummaries(participantIds: List<String>): Map<String, LinkResultSummary> {
        if (participantIds.isEmpty()) return emptyMap()
        return OrienteeringResults.selectAll()
            .where { OrienteeringResults.participantId inList participantIds }
            .associate {
                it[OrienteeringResults.participantId] to LinkResultSummary(
                    rank = it[OrienteeringResults.rank],
                    totalTime = it[OrienteeringResults.totalTime],
                    totalScore = it[OrienteeringResults.totalScore],
                    status = it[OrienteeringResults.status],
                )
            }
    }

    private fun participantsWithCompetition() = OrienteeringParticipants
        .join(Competitions, JoinType.INNER, OrienteeringParticipants.competitionId, Competitions.id)
        .selectAll()

    private fun myRequestsQuery() = ParticipantLinkRequests
        .join(OrienteeringParticipants, JoinType.INNER, ParticipantLinkRequests.participantId, OrienteeringParticipants.id)
        .join(Competitions, JoinType.INNER, ParticipantLinkRequests.competitionId, Competitions.id)
        .selectAll()

    private fun myRequestResponse(requestId: String): LinkRequestResponse =
        myRequestsQuery().where { ParticipantLinkRequests.id eq requestId }.single().toMyResponse()

    private fun ResultRow.toMyResponse() = LinkRequestResponse(
        id = this[ParticipantLinkRequests.id],
        participantId = this[ParticipantLinkRequests.participantId],
        competitionId = this[ParticipantLinkRequests.competitionId],
        competitionTitle = this[Competitions.title],
        competitionStartDate = this[Competitions.startDate],
        participantFirstName = this[OrienteeringParticipants.firstName],
        participantLastName = this[OrienteeringParticipants.lastName],
        groupName = this[OrienteeringParticipants.groupName],
        status = this[ParticipantLinkRequests.status],
        source = this[ParticipantLinkRequests.requestSource],
        comment = this[ParticipantLinkRequests.comment],
        createdAt = this[ParticipantLinkRequests.createdAt],
    )

    private data class Notice(val userId: String, val title: String, val body: String, val data: Map<String, String>)

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_CANCELLED = "CANCELLED"
        /** Была одобрена, потом участника отвязали (сам пользователь или организатор). */
        const val STATUS_UNLINKED = "UNLINKED"
        val SOURCES = setOf("SUGGESTION", "MANUAL")

        const val KIND_REQUESTED = "participant_link_requested"
        const val KIND_REVIEWED = "participant_link_reviewed"

        private const val AUTO_REJECT_COMMENT = "Результат привязан к другому пользователю"
        private const val MAX_PENDING_PER_USER = 50L
        private const val MAX_SUGGESTIONS = 100
        private const val COMMENT_MAX_LENGTH = 500
    }
}
