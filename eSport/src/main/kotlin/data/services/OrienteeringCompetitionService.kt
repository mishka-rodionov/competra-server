package com.competra.data.services

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.exception.ConflictException
import com.competra.data.requests.orienteering.OrienteeringCompetitionRequest
import com.competra.data.response.orienteering.CompetitionDetailResponse
import com.competra.data.response.orienteering.CompetitionResponse
import com.competra.data.response.orienteering.CoordinatesResponse
import com.competra.data.response.orienteering.OrienteeringCompetitionResponse
import com.competra.data.response.orienteering.ParticipantGroupDetailResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringCompetitionService {

    private companion object {
        /** Статусы, которые НЕ должны переопределяться автоматическим пересчётом. */
        val MANUAL_STATUSES = listOf("IN_PROGRESS", "FINISHED", "DRAFT", "ARCHIVED")
    }

    /**
     * Вычисляет эффективный статус соревнования на основе сохранённого статуса и текущего времени.
     *
     * Статусы IN_PROGRESS и FINISHED задаются организатором вручную и не переопределяются.
     * Остальные статусы вычисляются динамически по датам регистрации и времени старта.
     */
    private fun buildOrienteeringResponse(
        comp: org.jetbrains.exposed.sql.ResultRow,
        orient: org.jetbrains.exposed.sql.ResultRow
    ): OrienteeringCompetitionResponse = OrienteeringCompetitionResponse(
        competitionId = orient[OrienteeringCompetitions.id],
        competition = CompetitionResponse(
            remoteId = comp[Competitions.id],
            title = comp[Competitions.title],
            startDate = comp[Competitions.startDate],
            endDate = comp[Competitions.endDate],
            kindOfSport = comp[Competitions.kindOfSport],
            description = comp[Competitions.description],
            address = comp[Competitions.address],
            mainOrganizerId = comp[Competitions.mainOrganizerId],
            coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
            else null,
            status = computeEffectiveStatus(
                storedStatus = comp[Competitions.status],
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                startTime = orient[OrienteeringCompetitions.startTime]
            ),
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            maxParticipants = comp[Competitions.maxParticipants],
            feeAmount = comp[Competitions.feeAmount],
            feeCurrency = comp[Competitions.feeCurrency],
            imageUrl = comp[Competitions.imageUrl],
            regulationUrl = comp[Competitions.regulationUrl],
            mapUrl = comp[Competitions.mapUrl],
            contactPhone = comp[Competitions.contactPhone],
            contactEmail = comp[Competitions.contactEmail],
            website = comp[Competitions.website],
            resultsStatus = comp[Competitions.resultsStatus],
            updatedAt = comp[Competitions.updatedAt]
        ),
        direction = orient[OrienteeringCompetitions.direction],
        punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
        startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
        countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
        startTime = orient[OrienteeringCompetitions.startTime],
        startIntervalSeconds = orient[OrienteeringCompetitions.startIntervalSeconds],
        updatedAt = orient[OrienteeringCompetitions.updatedAt]
    )

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

    /**
     * Точечно обновляет [Competitions.status] для соревнований, у которых наступило
     * новое временное состояние. Не трогает ручные/терминальные статусы
     * (`IN_PROGRESS`, `FINISHED`, `DRAFT`, `ARCHIVED`).
     *
     * Логика отражает [computeEffectiveStatus]:
     *   - startTime <= now → IN_PROGRESS
     *   - registrationEnd <= now → REGISTRATION_CLOSED
     *   - окно регистрации открыто и ещё не закрыто → REGISTRATION_OPEN
     *
     * Используется фоновым шедулером раз в 5 минут.
     *
     * @return суммарное число обновлённых строк за этот тик.
     */
    suspend fun recalculateAllStatuses(): Int = dbQuery {
        val now = System.currentTimeMillis()
        var total = 0

        // 1) → IN_PROGRESS: наступил OrienteeringCompetitions.startTime
        val toInProgressIds: List<Long> = (Competitions innerJoin OrienteeringCompetitions)
            .selectAll()
            .where {
                (Competitions.status notInList MANUAL_STATUSES) and
                OrienteeringCompetitions.startTime.isNotNull() and
                (OrienteeringCompetitions.startTime lessEq now)
            }
            .map { it[Competitions.id] }
        if (toInProgressIds.isNotEmpty()) {
            total += Competitions.update({ Competitions.id inList toInProgressIds }) {
                it[status] = "IN_PROGRESS"
                it[updatedAt] = now
            }
        }

        // 2) → REGISTRATION_CLOSED: окно регистрации закрылось
        total += Competitions.update({
            (Competitions.status inList listOf("CREATED", "REGISTRATION_OPEN")) and
                Competitions.registrationEnd.isNotNull() and
                (Competitions.registrationEnd lessEq now)
        }) {
            it[status] = "REGISTRATION_CLOSED"
            it[updatedAt] = now
        }

        // 3) → REGISTRATION_OPEN: окно регистрации открыто, ещё не закрыто
        total += Competitions.update({
            (Competitions.status eq "CREATED") and
                (Competitions.registrationStart.isNull() or (Competitions.registrationStart lessEq now)) and
                (Competitions.registrationEnd.isNull() or (Competitions.registrationEnd greater now))
        }) {
            it[status] = "REGISTRATION_OPEN"
            it[updatedAt] = now
        }

        total
    }

    suspend fun upsert(req: OrienteeringCompetitionRequest, userId: String): OrienteeringCompetitionResponse = dbQuery {
        val now = System.currentTimeMillis()

        // Server-wins: проверяем, что серверная запись не свежее, чем последняя
        // успешная синхронизация на клиенте. Иначе бросаем 409.
        val existingOrient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq req.competitionId }
            .singleOrNull()
        if (existingOrient != null && req.serverUpdatedAt != null &&
            req.serverUpdatedAt < existingOrient[OrienteeringCompetitions.updatedAt]
        ) {
            val existingComp = Competitions.selectAll()
                .where { Competitions.id eq existingOrient[OrienteeringCompetitions.competitionId] }
                .single()
            throw ConflictException(buildOrienteeringResponse(existingComp, existingOrient))
        }

        val competitionId: Long = if (req.competition.remoteId != null) {
            Competitions.update({ Competitions.id eq req.competition.remoteId }) {
                it[title] = req.competition.title
                it[startDate] = req.competition.startDate
                it[endDate] = req.competition.endDate
                it[kindOfSport] = req.competition.kindOfSport
                it[description] = req.competition.description
                it[address] = req.competition.address
                it[mainOrganizerId] = req.competition.mainOrganizerId
                it[latitude] = req.competition.coordinates?.latitude
                it[longitude] = req.competition.coordinates?.longitude
                it[status] = req.competition.status
                it[registrationStart] = req.competition.registrationStart
                it[registrationEnd] = req.competition.registrationEnd
                it[maxParticipants] = req.competition.maxParticipants
                it[feeAmount] = req.competition.feeAmount
                it[feeCurrency] = req.competition.feeCurrency
                it[imageUrl] = req.competition.imageUrl
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
                it[updatedAt] = now
            }
            req.competition.remoteId
        } else {
            Competitions.insert {
                it[title] = req.competition.title
                it[startDate] = req.competition.startDate
                it[endDate] = req.competition.endDate
                it[kindOfSport] = req.competition.kindOfSport
                it[description] = req.competition.description
                it[address] = req.competition.address
                it[mainOrganizerId] = req.competition.mainOrganizerId
                it[latitude] = req.competition.coordinates?.latitude
                it[longitude] = req.competition.coordinates?.longitude
                it[status] = if (req.competition.registrationStart == null) "REGISTRATION_OPEN" else "CREATED"
                it[registrationStart] = req.competition.registrationStart
                it[registrationEnd] = req.competition.registrationEnd
                it[maxParticipants] = req.competition.maxParticipants
                it[feeAmount] = req.competition.feeAmount
                it[feeCurrency] = req.competition.feeCurrency
                it[imageUrl] = req.competition.imageUrl
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
                it[updatedAt] = now
            } get Competitions.id
        }

        val existingOrienteering = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq req.competitionId }
            .singleOrNull()

        if (existingOrienteering == null) {
            OrienteeringCompetitions.insert {
                it[id] = req.competitionId
                it[OrienteeringCompetitions.competitionId] = competitionId
                it[OrienteeringCompetitions.userId] = userId
                it[direction] = req.direction
                it[punchingSystem] = req.punchingSystem
                it[startTimeMode] = req.startTimeMode
                it[countdownTimer] = req.countdownTimer
                it[startTime] = null
                it[startIntervalSeconds] = req.startIntervalSeconds
                it[updatedAt] = now
            }
        } else {
            OrienteeringCompetitions.update({ OrienteeringCompetitions.id eq req.competitionId }) {
                it[OrienteeringCompetitions.competitionId] = competitionId
                it[direction] = req.direction
                it[punchingSystem] = req.punchingSystem
                it[startTimeMode] = req.startTimeMode
                it[countdownTimer] = req.countdownTimer
                it[startIntervalSeconds] = req.startIntervalSeconds
                it[updatedAt] = now
            }
        }

        val comp = Competitions.selectAll().where { Competitions.id eq competitionId }.single()
        val orient = OrienteeringCompetitions.selectAll().where { OrienteeringCompetitions.id eq req.competitionId }.single()

        OrienteeringCompetitionResponse(
            competitionId = orient[OrienteeringCompetitions.id],
            competition = CompetitionResponse(
                remoteId = comp[Competitions.id],
                title = comp[Competitions.title],
                startDate = comp[Competitions.startDate],
                endDate = comp[Competitions.endDate],
                kindOfSport = comp[Competitions.kindOfSport],
                description = comp[Competitions.description],
                address = comp[Competitions.address],
                mainOrganizerId = comp[Competitions.mainOrganizerId],
                coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                    CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
                else null,
                status = computeEffectiveStatus(
                    storedStatus = comp[Competitions.status],
                    registrationStart = comp[Competitions.registrationStart],
                    registrationEnd = comp[Competitions.registrationEnd],
                    startTime = orient[OrienteeringCompetitions.startTime]
                ),
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                maxParticipants = comp[Competitions.maxParticipants],
                feeAmount = comp[Competitions.feeAmount],
                feeCurrency = comp[Competitions.feeCurrency],
                imageUrl = comp[Competitions.imageUrl],
                regulationUrl = comp[Competitions.regulationUrl],
                mapUrl = comp[Competitions.mapUrl],
                contactPhone = comp[Competitions.contactPhone],
                contactEmail = comp[Competitions.contactEmail],
                website = comp[Competitions.website],
                resultsStatus = comp[Competitions.resultsStatus],
                updatedAt = comp[Competitions.updatedAt]
            ),
            direction = orient[OrienteeringCompetitions.direction],
            punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
            startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
            countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
            startTime = orient[OrienteeringCompetitions.startTime],
            startIntervalSeconds = orient[OrienteeringCompetitions.startIntervalSeconds],
            updatedAt = orient[OrienteeringCompetitions.updatedAt]
        )
    }

    suspend fun getByUserId(userId: String): List<OrienteeringCompetitionResponse> = dbQuery {
        OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.userId eq userId }
            .mapNotNull { orient ->
                val comp = Competitions.selectAll()
                    .where { Competitions.id eq orient[OrienteeringCompetitions.competitionId] }
                    .singleOrNull() ?: return@mapNotNull null

                OrienteeringCompetitionResponse(
                    competitionId = orient[OrienteeringCompetitions.id],
                    competition = CompetitionResponse(
                        remoteId = comp[Competitions.id],
                        title = comp[Competitions.title],
                        startDate = comp[Competitions.startDate],
                        endDate = comp[Competitions.endDate],
                        kindOfSport = comp[Competitions.kindOfSport],
                        description = comp[Competitions.description],
                        address = comp[Competitions.address],
                        mainOrganizerId = comp[Competitions.mainOrganizerId],
                        coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                            CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
                        else null,
                        status = computeEffectiveStatus(
                            storedStatus = comp[Competitions.status],
                            registrationStart = comp[Competitions.registrationStart],
                            registrationEnd = comp[Competitions.registrationEnd],
                            startTime = orient[OrienteeringCompetitions.startTime]
                        ),
                        registrationStart = comp[Competitions.registrationStart],
                        registrationEnd = comp[Competitions.registrationEnd],
                        maxParticipants = comp[Competitions.maxParticipants],
                        feeAmount = comp[Competitions.feeAmount],
                        feeCurrency = comp[Competitions.feeCurrency],
                        imageUrl = comp[Competitions.imageUrl],
                        regulationUrl = comp[Competitions.regulationUrl],
                        mapUrl = comp[Competitions.mapUrl],
                        contactPhone = comp[Competitions.contactPhone],
                        contactEmail = comp[Competitions.contactEmail],
                        website = comp[Competitions.website],
                        resultsStatus = comp[Competitions.resultsStatus],
                        updatedAt = comp[Competitions.updatedAt]
                    ),
                    direction = orient[OrienteeringCompetitions.direction],
                    punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
                    startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
                    countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
                    startTime = orient[OrienteeringCompetitions.startTime],
                    startIntervalSeconds = orient[OrienteeringCompetitions.startIntervalSeconds],
                    updatedAt = orient[OrienteeringCompetitions.updatedAt]
                )
            }
    }

    /**
     * Возвращает список соревнований, на которые пользователь зарегистрирован как участник
     * (через таблицу OrienteeringParticipants).
     */
    suspend fun getRegisteredByUserId(userId: String): List<OrienteeringCompetitionResponse> = dbQuery {
        OrienteeringParticipants.selectAll()
            .where { OrienteeringParticipants.userId eq userId }
            .mapNotNull { participant ->
                val competitionId = participant[OrienteeringParticipants.competitionId]
                val orient = OrienteeringCompetitions.selectAll()
                    .where { OrienteeringCompetitions.competitionId eq competitionId }
                    .singleOrNull() ?: return@mapNotNull null
                val comp = Competitions.selectAll()
                    .where { Competitions.id eq competitionId }
                    .singleOrNull() ?: return@mapNotNull null

                OrienteeringCompetitionResponse(
                    competitionId = orient[OrienteeringCompetitions.id],
                    competition = CompetitionResponse(
                        remoteId = comp[Competitions.id],
                        title = comp[Competitions.title],
                        startDate = comp[Competitions.startDate],
                        endDate = comp[Competitions.endDate],
                        kindOfSport = comp[Competitions.kindOfSport],
                        description = comp[Competitions.description],
                        address = comp[Competitions.address],
                        mainOrganizerId = comp[Competitions.mainOrganizerId],
                        coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                            CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
                        else null,
                        status = computeEffectiveStatus(
                            storedStatus = comp[Competitions.status],
                            registrationStart = comp[Competitions.registrationStart],
                            registrationEnd = comp[Competitions.registrationEnd],
                            startTime = orient[OrienteeringCompetitions.startTime]
                        ),
                        registrationStart = comp[Competitions.registrationStart],
                        registrationEnd = comp[Competitions.registrationEnd],
                        maxParticipants = comp[Competitions.maxParticipants],
                        feeAmount = comp[Competitions.feeAmount],
                        feeCurrency = comp[Competitions.feeCurrency],
                        imageUrl = comp[Competitions.imageUrl],
                        regulationUrl = comp[Competitions.regulationUrl],
                        mapUrl = comp[Competitions.mapUrl],
                        contactPhone = comp[Competitions.contactPhone],
                        contactEmail = comp[Competitions.contactEmail],
                        website = comp[Competitions.website],
                        resultsStatus = comp[Competitions.resultsStatus],
                        updatedAt = comp[Competitions.updatedAt]
                    ),
                    direction = orient[OrienteeringCompetitions.direction],
                    punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
                    startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
                    countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
                    startTime = orient[OrienteeringCompetitions.startTime],
                    startIntervalSeconds = orient[OrienteeringCompetitions.startIntervalSeconds],
                    updatedAt = orient[OrienteeringCompetitions.updatedAt]
                )
            }
            .distinctBy { it.competitionId }
    }

    /**
     * Возвращает список публичных соревнований с применением фильтров.
     *
     * SQL-фильтры (виды спорта, диапазон дат старта) применяются на уровне БД.
     * Фильтр по статусу применяется в коде, потому что эффективный статус
     * вычисляется из дат через [computeEffectiveStatus] и не хранится напрямую.
     *
     * @param kindOfSports список наименований видов спорта (например, ["Orienteering"]). Пустой — без ограничения.
     * @param statuses список эффективных статусов (REGISTRATION_OPEN / IN_PROGRESS / FINISHED и др.). Комбинируется через OR. Пустой — без ограничения.
     * @param dateFrom нижняя граница [Competitions.startDate], inclusive. null — без ограничения.
     * @param dateTo верхняя граница [Competitions.startDate], inclusive. null — без ограничения.
     */
    suspend fun getPublicCompetitions(
        kindOfSports: List<String>,
        statuses: List<String>,
        dateFrom: Long?,
        dateTo: Long?
    ): List<CompetitionResponse> = dbQuery {
        val query = Competitions.selectAll()
        if (kindOfSports.isNotEmpty()) {
            query.andWhere { Competitions.kindOfSport inList kindOfSports }
        }
        if (dateFrom != null) {
            query.andWhere { Competitions.startDate greaterEq dateFrom }
        }
        if (dateTo != null) {
            query.andWhere { Competitions.startDate lessEq dateTo }
        }

        val items = query.map { comp ->
            CompetitionResponse(
                remoteId = comp[Competitions.id],
                title = comp[Competitions.title],
                startDate = comp[Competitions.startDate],
                endDate = comp[Competitions.endDate],
                kindOfSport = comp[Competitions.kindOfSport],
                description = comp[Competitions.description],
                address = comp[Competitions.address],
                mainOrganizerId = comp[Competitions.mainOrganizerId],
                coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                    CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
                else null,
                // Для публичного списка startTime недоступен без джойна — используем даты регистрации
                status = computeEffectiveStatus(
                    storedStatus = comp[Competitions.status],
                    registrationStart = comp[Competitions.registrationStart],
                    registrationEnd = comp[Competitions.registrationEnd],
                    startTime = null
                ),
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                maxParticipants = comp[Competitions.maxParticipants],
                feeAmount = comp[Competitions.feeAmount],
                feeCurrency = comp[Competitions.feeCurrency],
                imageUrl = comp[Competitions.imageUrl],
                regulationUrl = comp[Competitions.regulationUrl],
                mapUrl = comp[Competitions.mapUrl],
                contactPhone = comp[Competitions.contactPhone],
                contactEmail = comp[Competitions.contactEmail],
                website = comp[Competitions.website],
                resultsStatus = comp[Competitions.resultsStatus],
                updatedAt = comp[Competitions.updatedAt]
            )
        }

        if (statuses.isEmpty()) items else items.filter { it.status in statuses }
    }

    suspend fun getById(competitionId: Long, userId: String? = null): CompetitionDetailResponse? = dbQuery {
        val comp = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull() ?: return@dbQuery null

        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.competitionId eq competitionId }
            .singleOrNull()

        val groups = ParticipantGroups.selectAll()
            .where { ParticipantGroups.competitionId eq competitionId }
            .map { group ->
                val registeredCount = OrienteeringParticipants.selectAll()
                    .where { OrienteeringParticipants.groupId eq group[ParticipantGroups.id] }
                    .count().toInt()
                ParticipantGroupDetailResponse(
                    groupId = group[ParticipantGroups.id],
                    title = group[ParticipantGroups.title],
                    maxParticipants = group[ParticipantGroups.maxParticipants],
                    registeredCount = registeredCount
                )
            }

        val isUserRegistered = if (userId != null) {
            OrienteeringParticipants.selectAll()
                .where {
                    (OrienteeringParticipants.userId eq userId) and
                    (OrienteeringParticipants.competitionId eq competitionId)
                }
                .singleOrNull() != null
        } else false

        CompetitionDetailResponse(
            remoteId = comp[Competitions.id],
            title = comp[Competitions.title],
            startDate = comp[Competitions.startDate],
            endDate = comp[Competitions.endDate],
            kindOfSport = comp[Competitions.kindOfSport],
            description = comp[Competitions.description],
            address = comp[Competitions.address],
            mainOrganizerId = comp[Competitions.mainOrganizerId],
            coordinates = if (comp[Competitions.latitude] != null && comp[Competitions.longitude] != null)
                CoordinatesResponse(comp[Competitions.latitude]!!, comp[Competitions.longitude]!!)
            else null,
            status = computeEffectiveStatus(
                storedStatus = comp[Competitions.status],
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                startTime = orient?.get(OrienteeringCompetitions.startTime)
            ),
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            maxParticipants = comp[Competitions.maxParticipants],
            feeAmount = comp[Competitions.feeAmount],
            feeCurrency = comp[Competitions.feeCurrency],
            imageUrl = comp[Competitions.imageUrl],
            regulationUrl = comp[Competitions.regulationUrl],
            mapUrl = comp[Competitions.mapUrl],
            contactPhone = comp[Competitions.contactPhone],
            contactEmail = comp[Competitions.contactEmail],
            website = comp[Competitions.website],
            resultsStatus = comp[Competitions.resultsStatus],
            participantGroups = groups,
            isUserRegistered = isUserRegistered,
            updatedAt = comp[Competitions.updatedAt]
        )
    }

    /**
     * Удаляет соревнование и все связанные сущности через CASCADE FK
     * (orienteering_competitions, participant_groups, orienteering_participants,
     * orienteering_results, distances). Если CASCADE не настроен в БД — удаление
     * происходит вручную из связанных таблиц перед основной.
     */
    suspend fun deleteById(competitionId: Long): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        OrienteeringCompetitions.deleteWhere { OrienteeringCompetitions.competitionId eq competitionId }
        @Suppress("DEPRECATION")
        Competitions.deleteWhere { Competitions.id eq competitionId } > 0
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
