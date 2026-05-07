package com.sportenth.data.services

import com.sportenth.data.database.entity.Competitions
import com.sportenth.data.database.entity.OrienteeringCompetitions
import com.sportenth.data.database.entity.OrienteeringParticipants
import com.sportenth.data.database.entity.ParticipantGroups
import com.sportenth.data.requests.orienteering.OrienteeringCompetitionRequest
import com.sportenth.data.response.orienteering.CompetitionDetailResponse
import com.sportenth.data.response.orienteering.CompetitionResponse
import com.sportenth.data.response.orienteering.CoordinatesResponse
import com.sportenth.data.response.orienteering.OrienteeringCompetitionResponse
import com.sportenth.data.response.orienteering.ParticipantGroupDetailResponse
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class OrienteeringCompetitionService {

    /**
     * Вычисляет эффективный статус соревнования на основе сохранённого статуса и текущего времени.
     *
     * Статусы IN_PROGRESS и FINISHED задаются организатором вручную и не переопределяются.
     * Остальные статусы вычисляются динамически по датам регистрации и времени старта.
     */
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

    suspend fun upsert(req: OrienteeringCompetitionRequest, userId: String): OrienteeringCompetitionResponse = dbQuery {
        val now = System.currentTimeMillis()
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
     * Возвращает список соревнований, отфильтрованных по видам спорта.
     * Если список видов спорта пустой — возвращает все соревнования.
     *
     * @param kindOfSports список строковых наименований видов спорта (например, ["Orienteering"])
     */
    suspend fun getByKindOfSports(kindOfSports: List<String>): List<CompetitionResponse> = dbQuery {
        val query = if (kindOfSports.isEmpty()) {
            Competitions.selectAll()
        } else {
            Competitions.selectAll().where { Competitions.kindOfSport inList kindOfSports }
        }

        query.map { comp ->
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

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
