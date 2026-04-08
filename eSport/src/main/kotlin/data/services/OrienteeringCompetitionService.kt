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
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class OrienteeringCompetitionService {

    suspend fun upsert(req: OrienteeringCompetitionRequest, userId: String): OrienteeringCompetitionResponse = dbQuery {
        val competitionId = req.competition.remoteId ?: UUID.randomUUID().toString()

        val existingCompetition = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull()

        if (existingCompetition == null) {
            Competitions.insert {
                it[id] = competitionId
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
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
            }
        } else {
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
                it[registrationStart] = req.competition.registrationStart
                it[registrationEnd] = req.competition.registrationEnd
                it[maxParticipants] = req.competition.maxParticipants
                it[feeAmount] = req.competition.feeAmount
                it[feeCurrency] = req.competition.feeCurrency
                it[regulationUrl] = req.competition.regulationUrl
                it[mapUrl] = req.competition.mapUrl
                it[contactPhone] = req.competition.contactPhone
                it[contactEmail] = req.competition.contactEmail
                it[website] = req.competition.website
                it[resultsStatus] = req.competition.resultsStatus
            }
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
                it[startIntervalSeconds] = null
            }
        } else {
            OrienteeringCompetitions.update({ OrienteeringCompetitions.id eq req.competitionId }) {
                it[OrienteeringCompetitions.competitionId] = competitionId
                it[direction] = req.direction
                it[punchingSystem] = req.punchingSystem
                it[startTimeMode] = req.startTimeMode
                it[countdownTimer] = req.countdownTimer
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
                status = comp[Competitions.status],
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                maxParticipants = comp[Competitions.maxParticipants],
                feeAmount = comp[Competitions.feeAmount],
                feeCurrency = comp[Competitions.feeCurrency],
                regulationUrl = comp[Competitions.regulationUrl],
                mapUrl = comp[Competitions.mapUrl],
                contactPhone = comp[Competitions.contactPhone],
                contactEmail = comp[Competitions.contactEmail],
                website = comp[Competitions.website],
                resultsStatus = comp[Competitions.resultsStatus]
            ),
            direction = orient[OrienteeringCompetitions.direction],
            punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
            startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
            countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
            startTime = orient[OrienteeringCompetitions.startTime]
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
                        status = comp[Competitions.status],
                        registrationStart = comp[Competitions.registrationStart],
                        registrationEnd = comp[Competitions.registrationEnd],
                        maxParticipants = comp[Competitions.maxParticipants],
                        feeAmount = comp[Competitions.feeAmount],
                        feeCurrency = comp[Competitions.feeCurrency],
                        regulationUrl = comp[Competitions.regulationUrl],
                        mapUrl = comp[Competitions.mapUrl],
                        contactPhone = comp[Competitions.contactPhone],
                        contactEmail = comp[Competitions.contactEmail],
                        website = comp[Competitions.website],
                        resultsStatus = comp[Competitions.resultsStatus]
                    ),
                    direction = orient[OrienteeringCompetitions.direction],
                    punchingSystem = orient[OrienteeringCompetitions.punchingSystem],
                    startTimeMode = orient[OrienteeringCompetitions.startTimeMode],
                    countdownTimer = orient[OrienteeringCompetitions.countdownTimer],
                    startTime = orient[OrienteeringCompetitions.startTime]
                )
            }
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
                status = comp[Competitions.status],
                registrationStart = comp[Competitions.registrationStart],
                registrationEnd = comp[Competitions.registrationEnd],
                maxParticipants = comp[Competitions.maxParticipants],
                feeAmount = comp[Competitions.feeAmount],
                feeCurrency = comp[Competitions.feeCurrency],
                regulationUrl = comp[Competitions.regulationUrl],
                mapUrl = comp[Competitions.mapUrl],
                contactPhone = comp[Competitions.contactPhone],
                contactEmail = comp[Competitions.contactEmail],
                website = comp[Competitions.website],
                resultsStatus = comp[Competitions.resultsStatus]
            )
        }
    }

    suspend fun getById(competitionId: String): CompetitionDetailResponse? = dbQuery {
        val comp = Competitions.selectAll()
            .where { Competitions.id eq competitionId }
            .singleOrNull() ?: return@dbQuery null

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
            status = comp[Competitions.status],
            registrationStart = comp[Competitions.registrationStart],
            registrationEnd = comp[Competitions.registrationEnd],
            maxParticipants = comp[Competitions.maxParticipants],
            feeAmount = comp[Competitions.feeAmount],
            feeCurrency = comp[Competitions.feeCurrency],
            regulationUrl = comp[Competitions.regulationUrl],
            mapUrl = comp[Competitions.mapUrl],
            contactPhone = comp[Competitions.contactPhone],
            contactEmail = comp[Competitions.contactEmail],
            website = comp[Competitions.website],
            resultsStatus = comp[Competitions.resultsStatus],
            participantGroups = groups
        )
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
