package com.competra.data.services

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.Distances
import com.competra.data.database.entity.OrienteeringCompetitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.exception.ConflictException
import com.competra.data.requests.orienteering.OrienteeringCompetitionRequest
import com.competra.data.response.base.PagedResponse
import com.competra.data.response.orienteering.CompetitionDetailResponse
import com.competra.data.response.orienteering.CompetitionResponse
import com.competra.data.response.orienteering.CoordinatesResponse
import com.competra.data.response.orienteering.OrienteeringCompetitionResponse
import com.competra.data.response.orienteering.ParticipantGroupDetailResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Case
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringCompetitionService {

    private companion object {
        /** Статусы, которые НЕ должны переопределяться автоматическим пересчётом. */
        val MANUAL_STATUSES = listOf("IN_PROGRESS", "FINISHED", "DRAFT", "ARCHIVED")

        /** Терминальные статусы, которые [computeEffectiveStatus] возвращает как есть, без пересчёта по датам. */
        val TERMINAL_STATUSES = listOf("IN_PROGRESS", "FINISHED")
    }

    /** Маппинг строки ядра в [CompetitionResponse]. startTime берётся из расширения (если есть). */
    private fun compToResponse(comp: ResultRow, startTime: Long?): CompetitionResponse = CompetitionResponse(
        id = comp[Competitions.id],
        legacyId = comp[Competitions.legacyId],
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
            startTime = startTime
        ),
        registrationStart = comp[Competitions.registrationStart],
        registrationEnd = comp[Competitions.registrationEnd],
        maxParticipants = comp[Competitions.maxParticipants],
        feeAmount = comp[Competitions.feeAmount],
        feeCurrency = comp[Competitions.feeCurrency],
        imageUrl = comp[Competitions.imageUrl],
        coverCropX = comp[Competitions.coverCropX],
        coverCropY = comp[Competitions.coverCropY],
        coverCropWidth = comp[Competitions.coverCropWidth],
        coverCropHeight = comp[Competitions.coverCropHeight],
        regulationUrl = comp[Competitions.regulationUrl],
        mapUrl = comp[Competitions.mapUrl],
        resultsUrl = comp[Competitions.resultsUrl],
        contactPhone = comp[Competitions.contactPhone],
        contactEmail = comp[Competitions.contactEmail],
        website = comp[Competitions.website],
        resultsStatus = comp[Competitions.resultsStatus],
        timeZoneId = comp[Competitions.timeZoneId],
        isTest = comp[Competitions.isTest],
        updatedAt = comp[Competitions.updatedAt]
    )

    /** Маппинг пары (ядро, расширение) в [OrienteeringCompetitionResponse]. */
    private fun buildOrienteeringResponse(
        comp: ResultRow,
        orient: ResultRow
    ): OrienteeringCompetitionResponse = OrienteeringCompetitionResponse(
        competitionId = comp[Competitions.id],
        competition = compToResponse(comp, orient[OrienteeringCompetitions.startTime]),
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
     * Используется фоновым шедулером раз в 5 минут.
     *
     * @return суммарное число обновлённых строк за этот тик.
     */
    suspend fun recalculateAllStatuses(): Int = dbQuery {
        val now = System.currentTimeMillis()
        var total = 0

        // 1) → IN_PROGRESS: наступил OrienteeringCompetitions.startTime.
        // Связь 1:1 по общему UUID: Competitions.id == OrienteeringCompetitions.id.
        val toInProgressIds: List<String> = Competitions
            .join(
                otherTable = OrienteeringCompetitions,
                joinType = JoinType.INNER,
                onColumn = Competitions.id,
                otherColumn = OrienteeringCompetitions.id
            )
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
        val competitionId = req.competitionId

        // Server-wins: если клиент знает запись старее серверной — бросаем 409 с текущими данными сервера.
        // Сравниваем с OrienteeringCompetitions.updatedAt (расширение), а НЕ с Competitions.updatedAt:
        // CompetitionStatusScheduler автоматически обновляет comp.updatedAt при смене статуса, не трогая
        // orient.updatedAt. Иначе любое плановое обновление статуса порождало бы ложные 409.
        val existingOrient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()
        val serverTs = existingOrient?.get(OrienteeringCompetitions.updatedAt) ?: 0L
        if (existingOrient != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
            req.serverUpdatedAt < serverTs
        ) {
            val existingComp = Competitions.selectAll()
                .where { Competitions.id eq competitionId }
                .single()
            throw ConflictException(buildOrienteeringResponse(existingComp, existingOrient), req.serverUpdatedAt, serverTs)
        }

        val existingComp = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull()

        if (existingComp != null) {
            Competitions.update({ Competitions.id eq competitionId }) {
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
                it[coverCropX] = req.competition.coverCropX
                it[coverCropY] = req.competition.coverCropY
                it[coverCropWidth] = req.competition.coverCropWidth
                it[coverCropHeight] = req.competition.coverCropHeight
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[resultsUrl] = req.competition.resultsUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
                it[timeZoneId] = req.competition.timeZoneId
                it[updatedAt] = now
            }
        } else {
            Competitions.insert {
                it[id] = competitionId
                it[ownerId] = userId
                it[title] = req.competition.title
                it[startDate] = req.competition.startDate
                it[endDate] = req.competition.endDate
                it[kindOfSport] = req.competition.kindOfSport
                it[description] = req.competition.description
                it[address] = req.competition.address
                it[mainOrganizerId] = req.competition.mainOrganizerId
                it[latitude] = req.competition.coordinates?.latitude
                it[longitude] = req.competition.coordinates?.longitude
                it[isTest] = req.competition.isTest
                it[status] = if (req.competition.registrationStart == null) "REGISTRATION_OPEN" else "CREATED"
                it[registrationStart] = req.competition.registrationStart
                it[registrationEnd] = req.competition.registrationEnd
                it[maxParticipants] = req.competition.maxParticipants
                it[feeAmount] = req.competition.feeAmount
                it[feeCurrency] = req.competition.feeCurrency
                it[imageUrl] = req.competition.imageUrl
                it[coverCropX] = req.competition.coverCropX
                it[coverCropY] = req.competition.coverCropY
                it[coverCropWidth] = req.competition.coverCropWidth
                it[coverCropHeight] = req.competition.coverCropHeight
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[resultsUrl] = req.competition.resultsUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
                it[timeZoneId] = req.competition.timeZoneId
                it[updatedAt] = now
            }
        }

        if (existingOrient == null) {
            OrienteeringCompetitions.insert {
                it[id] = competitionId
                it[direction] = req.direction
                it[punchingSystem] = req.punchingSystem
                it[startTimeMode] = req.startTimeMode
                it[countdownTimer] = req.countdownTimer
                it[startTime] = null
                it[startIntervalSeconds] = req.startIntervalSeconds
                it[updatedAt] = now
            }
        } else {
            OrienteeringCompetitions.update({ OrienteeringCompetitions.id eq competitionId }) {
                it[direction] = req.direction
                it[punchingSystem] = req.punchingSystem
                it[startTimeMode] = req.startTimeMode
                it[countdownTimer] = req.countdownTimer
                it[startIntervalSeconds] = req.startIntervalSeconds
                it[updatedAt] = now
            }
        }

        val comp = Competitions.selectAll().where { Competitions.id eq competitionId }.single()
        val orient = OrienteeringCompetitions.selectAll().where { OrienteeringCompetitions.id eq competitionId }.single()
        buildOrienteeringResponse(comp, orient)
    }

    suspend fun getByUserId(userId: String): List<OrienteeringCompetitionResponse> = dbQuery {
        Competitions.selectAll()
            .where { Competitions.ownerId eq userId }
            .mapNotNull { comp ->
                val orient = OrienteeringCompetitions.selectAll()
                    .where { OrienteeringCompetitions.id eq comp[Competitions.id] }
                    .singleOrNull() ?: return@mapNotNull null
                buildOrienteeringResponse(comp, orient)
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
                    .where { OrienteeringCompetitions.id eq competitionId }
                    .singleOrNull() ?: return@mapNotNull null
                val comp = Competitions.selectAll()
                    .where { Competitions.id eq competitionId }
                    .singleOrNull() ?: return@mapNotNull null
                buildOrienteeringResponse(comp, orient)
            }
            .distinctBy { it.competitionId }
    }

    /**
     * SQL-версия [computeEffectiveStatus] для публичного списка, где startTime всегда
     * недоступен (нет джойна на OrienteeringCompetitions) — поэтому ветка IN_PROGRESS-по-startTime
     * здесь не нужна. Должна давать тот же результат, что и Kotlin-версия при startTime == null,
     * иначе фильтр по статусу и отображаемые карточки разъедутся.
     */
    private fun SqlExpressionBuilder.effectiveStatusExpr(now: Long): ExpressionWithColumnType<String> =
        Case()
            .When(Competitions.status inList TERMINAL_STATUSES, Competitions.status)
            .When(
                Competitions.registrationEnd.isNotNull() and (Competitions.registrationEnd lessEq now),
                stringLiteral("REGISTRATION_CLOSED")
            )
            .When(
                Competitions.registrationStart.isNull() or (Competitions.registrationStart lessEq now),
                stringLiteral("REGISTRATION_OPEN")
            )
            .Else(stringLiteral("CREATED"))

    /**
     * Возвращает страницу публичных соревнований с применением фильтров.
     *
     * Все фильтры, включая статус, применяются на уровне БД (статус — через SQL CASE,
     * см. [effectiveStatusExpr]), поэтому LIMIT/OFFSET корректно режут уже отфильтрованный
     * и отсортированный по дате старта набор. hasMore вычисляется без отдельного COUNT-запроса:
     * запрашивается на 1 запись больше limit.
     */
    suspend fun getPublicCompetitions(
        kindOfSports: List<String>,
        statuses: List<String>,
        dateFrom: Long?,
        dateTo: Long?,
        includeTest: Boolean = false,
        page: Int = 0,
        limit: Int = 20
    ): PagedResponse<CompetitionResponse> = dbQuery {
        val now = System.currentTimeMillis()
        val query = Competitions.selectAll()
        // Тестовые соревнования скрыты из публичной ленты; includeTest=true — debug-предпросмотр.
        if (!includeTest) {
            query.andWhere { Competitions.isTest eq false }
        }
        if (kindOfSports.isNotEmpty()) {
            query.andWhere { Competitions.kindOfSport inList kindOfSports }
        }
        if (dateFrom != null) {
            query.andWhere { Competitions.startDate greaterEq dateFrom }
        }
        if (dateTo != null) {
            query.andWhere { Competitions.startDate lessEq dateTo }
        }
        if (statuses.isNotEmpty()) {
            query.andWhere { effectiveStatusExpr(now) inList statuses }
        }
        query.orderBy(Competitions.startDate, SortOrder.DESC)
        query.limit(limit + 1).offset(page.toLong() * limit)

        // Для публичного списка startTime недоступен без джойна — используем даты регистрации.
        val rows = query.map { comp -> compToResponse(comp, startTime = null) }
        PagedResponse(items = rows.take(limit), hasMore = rows.size > limit)
    }

    suspend fun getById(competitionId: String, userId: String? = null): CompetitionDetailResponse? = dbQuery {
        val comp = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull() ?: return@dbQuery null

        val orient = OrienteeringCompetitions.selectAll()
            .where { OrienteeringCompetitions.id eq competitionId }
            .singleOrNull()

        val groups = ParticipantGroups
            .join(Distances, JoinType.LEFT, ParticipantGroups.distanceId, Distances.id)
            .selectAll()
            .where { ParticipantGroups.competitionId eq competitionId }
            .map { row ->
                val registeredCount = OrienteeringParticipants.selectAll()
                    .where { OrienteeringParticipants.groupId eq row[ParticipantGroups.id] }
                    .count().toInt()
                ParticipantGroupDetailResponse(
                    groupId = row[ParticipantGroups.id],
                    title = row[ParticipantGroups.title],
                    maxParticipants = row[ParticipantGroups.maxParticipants],
                    registeredCount = registeredCount,
                    distanceName = row[Distances.name],
                    distanceLengthMeters = row[Distances.lengthMeters],
                    distanceClimbMeters = row[Distances.climbMeters],
                    distanceControlsCount = row[Distances.controlsCount],
                    distanceDescription = row[Distances.description]
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
            id = comp[Competitions.id],
            legacyId = comp[Competitions.legacyId],
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
            coverCropX = comp[Competitions.coverCropX],
            coverCropY = comp[Competitions.coverCropY],
            coverCropWidth = comp[Competitions.coverCropWidth],
            coverCropHeight = comp[Competitions.coverCropHeight],
            regulationUrl = comp[Competitions.regulationUrl],
            mapUrl = comp[Competitions.mapUrl],
            resultsUrl = comp[Competitions.resultsUrl],
            contactPhone = comp[Competitions.contactPhone],
            contactEmail = comp[Competitions.contactEmail],
            website = comp[Competitions.website],
            resultsStatus = comp[Competitions.resultsStatus],
            timeZoneId = comp[Competitions.timeZoneId],
            participantGroups = groups,
            isUserRegistered = isUserRegistered,
            isTest = comp[Competitions.isTest],
            updatedAt = orient?.get(OrienteeringCompetitions.updatedAt) ?: comp[Competitions.updatedAt]
        )
    }

    /**
     * Переходный helper: ищет соревнование по новому UUID, а если параметр числовой —
     * по [Competitions.legacyId]. Позволяет старым клиентам, ещё знающим прежний BIGINT,
     * читать соревнование во время миграции на UUID.
     */
    suspend fun getByIdOrLegacy(rawId: String, userId: String? = null): CompetitionDetailResponse? {
        getById(rawId, userId)?.let { return it }
        val legacy = rawId.toLongOrNull() ?: return null
        val resolvedId = dbQuery {
            Competitions.selectAll()
                .where { Competitions.legacyId eq legacy }
                .singleOrNull()
                ?.get(Competitions.id)
        } ?: return null
        return getById(resolvedId, userId)
    }

    /**
     * Удаляет соревнование. Связанные сущности удаляются каскадом через FK
     * (orienteering_competitions, participant_groups, orienteering_participants,
     * orienteering_results, distances).
     */
    suspend fun deleteById(competitionId: String): Boolean = dbQuery {
        @Suppress("DEPRECATION")
        Competitions.deleteWhere { Competitions.id eq competitionId } > 0
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
