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
import com.competra.data.response.orienteering.LinkCandidateResponse
import com.competra.data.response.orienteering.OrganizerLinkResultResponse
import com.competra.data.response.rating.RatingStandingEntry
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.competra.data.database.ioTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.jdbc.select

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

        // Тестовые соревнования видны только тем, кто ими управляет, — и в подсказках тоже.
        val manageableCompetitionIds = competitionIdsWithPermission(userId, CompetitionPermission.MANAGE_PARTICIPANTS)
        val rows = participantsWithCompetition()
            .where {
                (OrienteeringParticipants.id inList matchedIds) and
                    ((Competitions.isTest eq false) or (Competitions.id inList manageableCompetitionIds))
            }
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
                // Тестовое соревнование чужим не видно — отвечаем так же, как на несуществующего участника.
                if (isTestCompetition(competitionId) &&
                    !hasCompetitionPermission(competitionId, userId, CompetitionPermission.MANAGE_PARTICIPANTS)
                ) {
                    throw NotFoundException("Участник не найден")
                }
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
            // data-only: иначе в фоне Android рисует уведомление сам, и тап не ведёт на экран заявок.
            fcmService.sendToUser(
                userId = notice.userId,
                title = notice.title,
                body = notice.body,
                data = notice.data,
                includeNotificationBlock = false,
            )
        }.onFailure { log.warn("Failed to send link request push to userId=${notice.userId}", it) }
    }

    /**
     * Аккаунты, к которым организатор может привязать участника [participantId]: фамилия (или
     * «фамилия имя») начинается с [query], по умолчанию — с фамилии участника. Полное совпадение ФИО — первыми.
     */
    suspend fun linkCandidates(participantId: String, requesterId: String, query: String?): List<LinkCandidateResponse> =
        dbQuery {
            val participant = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq participantId }
                .singleOrNull() ?: throw NotFoundException("Участник не найден")
            val competitionId = participant[OrienteeringParticipants.competitionId]
            requireParticipantEditAccess(competitionId, requesterId)

            val needle = normalizePersonName(query?.takeIf { it.isNotBlank() } ?: participant[OrienteeringParticipants.lastName])
            if (needle.length < MIN_CANDIDATE_QUERY) return@dbQuery emptyList()

            val competition = Competitions.selectAll().where { Competitions.id eq competitionId }.single()
            val year = competitionYear(competition[Competitions.startDate], competition[Competitions.timeZoneId])
            val group = ParticipantGroups.selectAll()
                .where { ParticipantGroups.id eq participant[OrienteeringParticipants.groupId] }
                .singleOrNull()
            val usersInCompetition = OrienteeringParticipants.select(OrienteeringParticipants.userId)
                .where { OrienteeringParticipants.competitionId eq competitionId }
                .mapNotNullTo(mutableSetOf()) { it[OrienteeringParticipants.userId]?.takeIf(String::isNotBlank) }
            val pFirst = participant[OrienteeringParticipants.firstName]
            val pLast = participant[OrienteeringParticipants.lastName]

            UserService.Users.selectAll()
                .mapNotNull { row ->
                    val first = row[UserService.Users.firstName]
                    val last = row[UserService.Users.lastName]
                    val middle = row[UserService.Users.middleName].ifBlank { null }
                    val nameMatches = namesMatch(first, last, middle, pFirst, pLast)
                    val matchesQuery = normalizePersonName(last).startsWith(needle) ||
                        normalizePersonName("$last $first").startsWith(needle)
                    if (!nameMatches && !matchesQuery) return@mapNotNull null
                    val userBirthDate = row[UserService.Users.birthDate]
                    val userGender = row[UserService.Users.gender]
                    LinkCandidateResponse(
                        userId = row[UserService.Users.id],
                        firstName = first,
                        lastName = last,
                        middleName = middle,
                        birthYear = parseBirthYear(userBirthDate),
                        gender = userGender,
                        nameMatches = nameMatches,
                        eligibilityWarning = group?.let {
                            linkEligibilityWarning(
                                groupTitle = it[ParticipantGroups.title],
                                groupGender = it[ParticipantGroups.gender],
                                minAge = it[ParticipantGroups.minAge],
                                maxAge = it[ParticipantGroups.maxAge],
                                userGender = userGender,
                                userBirthDate = userBirthDate,
                                competitionYear = year,
                            )
                        },
                        alreadyInCompetition = row[UserService.Users.id] in usersInCompetition,
                    )
                }
                .sortedWith(
                    compareByDescending<LinkCandidateResponse> { it.nameMatches }
                        .thenBy { normalizePersonName(it.lastName) }
                        .thenBy { normalizePersonName(it.firstName) }
                )
                .take(MAX_CANDIDATES)
        }

    /**
     * Организатор сам привязывает вручную внесённых участников к аккаунту [userId] — без заявки
     * пользователя. Всё или ничего: права на каждое соревнование, один участник на аккаунт в соревновании.
     * Заявки на этих участников закрываются, пользователь получает пуш и может отвязать результат сам.
     */
    suspend fun linkByOrganizer(organizerId: String, userId: String, participantIds: List<String>): OrganizerLinkResultResponse {
        if (participantIds.isEmpty()) throw UnprocessableEntityException("Не выбраны результаты")
        val (count, notices) = dbQuery {
            linkLocked(organizerId, userId, participantIds.distinct(), System.currentTimeMillis())
        }
        notices.forEach { send(it) }
        return OrganizerLinkResultResponse(count)
    }

    /** Организатор приводит непривязанных участников к одному написанию ФИО. */
    suspend fun renameByOrganizer(
        organizerId: String,
        participantIds: List<String>,
        firstName: String,
        lastName: String,
    ): OrganizerLinkResultResponse {
        val first = firstName.trim()
        val last = lastName.trim()
        if (last.isEmpty()) throw UnprocessableEntityException("Укажите фамилию")
        if (first.length > NAME_MAX_LENGTH || last.length > NAME_MAX_LENGTH) {
            throw UnprocessableEntityException("Слишком длинное имя")
        }
        if (participantIds.isEmpty()) throw UnprocessableEntityException("Не выбраны результаты")
        return dbQuery {
            OrganizerLinkResultResponse(renameLocked(organizerId, participantIds.distinct(), first, last))
        }
    }

    /**
     * Объединение строк таблицы рейтинга: если основная строка — аккаунт, участники остальных строк
     * привязываются к нему; если обе без аккаунта — переименовываются в ФИО основной.
     */
    suspend fun mergeStandings(
        organizerId: String,
        standings: List<RatingStandingEntry>,
        targetKey: String,
        sourceKeys: List<String>,
    ): OrganizerLinkResultResponse {
        val byKey = standings.associateBy { it.participantKey }
        val target = byKey[targetKey] ?: throw NotFoundException("Строка рейтинга не найдена")
        val sources = sourceKeys.distinct()
            .filter { it != targetKey }
            .map { byKey[it] ?: throw NotFoundException("Строка рейтинга не найдена") }
        if (sources.isEmpty()) throw UnprocessableEntityException("Выберите строки для объединения")
        val participantIds = sources.flatMap { source -> source.breakdown.map { it.participantId } }.distinct()

        val targetUserId = target.userId
        if (targetUserId != null) {
            if (sources.any { it.userId != null }) throw UnprocessableEntityException("Нельзя объединить два аккаунта")
            return linkByOrganizer(organizerId, targetUserId, participantIds)
        }
        if (sources.any { it.userId != null }) {
            throw UnprocessableEntityException("Основной выберите строку с аккаунтом — к ней привяжутся остальные")
        }
        return renameByOrganizer(organizerId, participantIds, target.firstName, target.lastName)
    }

    private fun linkLocked(
        organizerId: String,
        userId: String,
        participantIds: List<String>,
        now: Long,
    ): Pair<Int, List<Notice>> {
        UserService.Users.selectAll()
            .where { UserService.Users.id eq userId }
            .singleOrNull() ?: throw NotFoundException("Пользователь не найден")
        val notices = mutableListOf<Notice>()
        val linkedCompetitions = mutableListOf<String>()

        for (participantId in participantIds) {
            val participant = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq participantId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Участник не найден")
            val competitionId = participant[OrienteeringParticipants.competitionId]
            requireParticipantEditAccess(competitionId, organizerId)
            val linkedUserId = participant[OrienteeringParticipants.userId]?.takeIf { it.isNotBlank() }
            if (linkedUserId == userId) continue
            if (linkedUserId != null) {
                throw UnprocessableEntityException("Результат «${participant.fullName()}» уже привязан к другому пользователю")
            }
            if (userParticipantIn(competitionId, userId, exceptParticipantId = participantId) != null) {
                throw UnprocessableEntityException(
                    "У пользователя уже есть результат в соревновании «${competitionTitle(competitionId)}». " +
                        "Удалите дубль, затем привяжите"
                )
            }
            OrienteeringParticipants.update({ OrienteeringParticipants.id eq participantId }) {
                it[OrienteeringParticipants.userId] = userId
                it[updatedAt] = now
            }

            // Заявки на этого участника решены: свою — одобряем, чужие — отклоняем.
            ParticipantLinkRequests.selectAll()
                .where {
                    (ParticipantLinkRequests.participantId eq participantId) and
                        (ParticipantLinkRequests.status eq STATUS_PENDING)
                }
                .toList()
                .forEach { request ->
                    val claimantId = request[ParticipantLinkRequests.userId]
                    val approved = claimantId == userId
                    ParticipantLinkRequests.update({ ParticipantLinkRequests.id eq request[ParticipantLinkRequests.id] }) {
                        it[status] = if (approved) STATUS_APPROVED else STATUS_REJECTED
                        if (!approved) it[comment] = AUTO_REJECT_COMMENT
                        it[reviewedBy] = organizerId
                        it[reviewedAt] = now
                        it[updatedAt] = now
                    }
                    if (!approved) notices += rejectedNotice(claimantId, competitionId, AUTO_REJECT_COMMENT)
                }
            linkedCompetitions += competitionId
        }

        if (linkedCompetitions.isNotEmpty() && userId != organizerId) {
            val first = linkedCompetitions.first()
            val what = if (linkedCompetitions.size == 1) "результат — «${competitionTitle(first)}»"
            else "результаты: ${linkedCompetitions.size}"
            notices += Notice(
                userId = userId,
                title = "Результаты привязаны к профилю",
                body = "Организатор привязал к вашему профилю $what. Если это не вы — отвяжите в профиле",
                // Тот же kind, что у одобренной заявки: клиенты уже ведут его на экран привязок.
                data = mapOf("competition_id" to first, "kind" to KIND_REVIEWED, "approved" to "true"),
            )
        }
        return linkedCompetitions.size to notices
    }

    private fun renameLocked(organizerId: String, participantIds: List<String>, firstName: String, lastName: String): Int {
        val now = System.currentTimeMillis()
        var count = 0
        for (participantId in participantIds) {
            val participant = OrienteeringParticipants.selectAll()
                .where { OrienteeringParticipants.id eq participantId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Участник не найден")
            requireParticipantEditAccess(participant[OrienteeringParticipants.competitionId], organizerId)
            if (!participant[OrienteeringParticipants.userId].isNullOrBlank()) {
                throw UnprocessableEntityException(
                    "«${participant.fullName()}» привязан к аккаунту — объедините с ним, а не переименовывайте"
                )
            }
            if (participant[OrienteeringParticipants.firstName] == firstName &&
                participant[OrienteeringParticipants.lastName] == lastName
            ) continue
            OrienteeringParticipants.update({ OrienteeringParticipants.id eq participantId }) {
                it[OrienteeringParticipants.firstName] = firstName
                it[OrienteeringParticipants.lastName] = lastName
                it[updatedAt] = now
            }
            count++
        }
        return count
    }

    private fun isUnlinked(): Op<Boolean> =
        OrienteeringParticipants.userId.isNull() or (OrienteeringParticipants.userId eq "")

    /** Участник соревнования, уже привязанный к [userId] (кроме [exceptParticipantId]). */
    private fun userParticipantIn(competitionId: String, userId: String, exceptParticipantId: String?): ResultRow? =
        OrienteeringParticipants.selectAll()
            .where { (OrienteeringParticipants.competitionId eq competitionId) and (OrienteeringParticipants.userId eq userId) }
            .firstOrNull { it[OrienteeringParticipants.id] != exceptParticipantId }

    private fun isTestCompetition(competitionId: String): Boolean =
        Competitions.select(Competitions.isTest)
            .where { Competitions.id eq competitionId }
            .singleOrNull()?.get(Competitions.isTest) ?: false

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
        ioTransaction { block() }

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
        private const val MIN_CANDIDATE_QUERY = 2
        private const val MAX_CANDIDATES = 20
        private const val NAME_MAX_LENGTH = 200
    }
}
