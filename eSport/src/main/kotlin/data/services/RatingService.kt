package com.competra.data.services

import com.competra.data.database.entity.Competitions
import com.competra.data.database.entity.OrienteeringParticipants
import com.competra.data.database.entity.OrienteeringResults
import com.competra.data.database.entity.ParticipantGroups
import com.competra.data.database.entity.clubs.ClubMembers
import com.competra.data.database.entity.clubs.Clubs
import com.competra.data.database.entity.rating.RatingCompetitions
import com.competra.data.database.entity.rating.RatingGroupMappings
import com.competra.data.database.entity.rating.RatingGroups
import com.competra.data.database.entity.rating.RatingSeries
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.rating.CreateRatingRequest
import com.competra.data.requests.rating.RatingGroupRequest
import com.competra.data.requests.rating.SetGroupMappingRequest
import com.competra.data.requests.rating.UpdateRatingRequest
import com.competra.data.response.base.PagedResponse
import com.competra.data.response.rating.AddCompetitionToRatingResponse
import com.competra.data.response.rating.RatingCompetitionResponse
import com.competra.data.response.rating.RatingGroupMappingSuggestionResponse
import com.competra.data.response.rating.RatingGroupResponse
import com.competra.data.response.rating.RatingResponse
import com.competra.data.response.rating.RatingSearchResponse
import com.competra.data.response.rating.RatingStandingBreakdownEntry
import com.competra.data.response.rating.RatingStandingEntry
import com.competra.data.response.rating.RatingStandingsResponse
import com.competra.data.util.RatingPointsTable
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Рейтинг всегда публичен для чтения: список/детали/standings доступны без авторизации, мутации — только FOUNDER/ADMIN клуба-владельца. */
class RatingService {

    suspend fun create(clubId: String, request: CreateRatingRequest, userId: String): RatingResponse = dbQuery {
        requireClubAdmin(clubId, userId)
        val now = System.currentTimeMillis()
        val ratingId = UUID.randomUUID().toString()

        RatingSeries.insert {
            it[id] = ratingId
            it[name] = request.name
            it[ownerClubId] = clubId
            it[createdAt] = now
            it[updatedAt] = now
        }
        insertGroups(ratingId, request.groups)

        buildResponse(ratingId)
    }

    suspend fun update(ratingId: String, request: UpdateRatingRequest, userId: String): RatingResponse = dbQuery {
        val rating = requireRating(ratingId)
        requireClubAdmin(rating[RatingSeries.ownerClubId], userId)
        val now = System.currentTimeMillis()

        RatingSeries.update({ RatingSeries.id eq ratingId }) {
            it[name] = request.name
            it[updatedAt] = now
        }
        RatingGroups.deleteWhere { RatingGroups.ratingId eq ratingId }
        insertGroups(ratingId, request.groups)

        buildResponse(ratingId)
    }

    suspend fun delete(ratingId: String, userId: String): Unit = dbQuery {
        val rating = requireRating(ratingId)
        requireClubAdmin(rating[RatingSeries.ownerClubId], userId)
        RatingSeries.deleteWhere { RatingSeries.id eq ratingId }
        Unit
    }

    suspend fun getById(ratingId: String): RatingResponse = dbQuery {
        buildResponse(ratingId)
    }

    suspend fun listForClub(clubId: String): List<RatingResponse> = dbQuery {
        RatingSeries.selectAll()
            .where { RatingSeries.ownerClubId eq clubId }
            .orderBy(RatingSeries.createdAt, SortOrder.DESC)
            .map { it.toResponse(loadGroups(it[RatingSeries.id])) }
    }

    /** Глобальный поиск по всем рейтингам (без привязки к клубу) — для экрана "Все рейтинги". */
    suspend fun search(query: String?, page: Int, limit: Int): PagedResponse<RatingSearchResponse> = dbQuery {
        val baseQuery = (RatingSeries innerJoin Clubs).selectAll()
        if (!query.isNullOrBlank()) {
            baseQuery.andWhere { RatingSeries.name like "%$query%" }
        }
        baseQuery.orderBy(RatingSeries.createdAt, SortOrder.DESC)
        baseQuery.limit(limit + 1).offset(page.toLong() * limit)

        val rows = baseQuery.toList()
        val hasMore = rows.size > limit
        val items = rows.take(limit).map {
            RatingSearchResponse(
                id = it[RatingSeries.id],
                name = it[RatingSeries.name],
                ownerClubId = it[RatingSeries.ownerClubId],
                ownerClubName = it[Clubs.name],
                createdAt = it[RatingSeries.createdAt]
            )
        }
        PagedResponse(items, hasMore)
    }

    suspend fun listCompetitions(ratingId: String): List<RatingCompetitionResponse> = dbQuery {
        (RatingCompetitions innerJoin Competitions)
            .selectAll()
            .where { RatingCompetitions.ratingId eq ratingId }
            .orderBy(RatingCompetitions.addedAt, SortOrder.DESC)
            .map {
                RatingCompetitionResponse(
                    id = it[RatingCompetitions.id],
                    ratingId = it[RatingCompetitions.ratingId],
                    competitionId = it[RatingCompetitions.competitionId],
                    competitionTitle = it[Competitions.title],
                    competitionStartDate = it[Competitions.startDate],
                    addedAt = it[RatingCompetitions.addedAt]
                )
            }
    }

    suspend fun addCompetition(ratingId: String, competitionId: String, userId: String): AddCompetitionToRatingResponse = dbQuery {
        val rating = requireRating(ratingId)
        requireClubAdmin(rating[RatingSeries.ownerClubId], userId)

        val competition = Competitions.selectAll().where { Competitions.id eq competitionId }.singleOrNull()
            ?: throw NotFoundException("Competition not found")

        val alreadyAdded = RatingCompetitions.selectAll()
            .where { (RatingCompetitions.ratingId eq ratingId) and (RatingCompetitions.competitionId eq competitionId) }
            .any()
        if (alreadyAdded) throw BadRequestException("Соревнование уже добавлено в этот рейтинг")

        val ratingCompetitionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        RatingCompetitions.insert {
            it[id] = ratingCompetitionId
            it[RatingCompetitions.ratingId] = ratingId
            it[RatingCompetitions.competitionId] = competitionId
            it[addedAt] = now
        }

        val ratingCompetitionResponse = RatingCompetitionResponse(
            id = ratingCompetitionId,
            ratingId = ratingId,
            competitionId = competitionId,
            competitionTitle = competition[Competitions.title],
            competitionStartDate = competition[Competitions.startDate],
            addedAt = now
        )

        AddCompetitionToRatingResponse(
            ratingCompetition = ratingCompetitionResponse,
            groupMappingSuggestions = suggestGroupMapping(ratingId, competitionId)
        )
    }

    /**
     * Подсказка маппинга групп соревнования на канонические группы рейтинга: если для группы уже есть
     * сохранённый маппинг — возвращается он (confidence=1), иначе — авто-подсказка по gender+возрасту.
     */
    suspend fun getGroupMappingSuggestions(ratingId: String, competitionId: String): List<RatingGroupMappingSuggestionResponse> = dbQuery {
        suggestGroupMapping(ratingId, competitionId)
    }

    suspend fun removeCompetition(ratingId: String, competitionId: String, userId: String): Unit = dbQuery {
        val rating = requireRating(ratingId)
        requireClubAdmin(rating[RatingSeries.ownerClubId], userId)
        RatingCompetitions.deleteWhere {
            (RatingCompetitions.ratingId eq ratingId) and (RatingCompetitions.competitionId eq competitionId)
        }
        Unit
    }

    suspend fun setGroupMapping(ratingId: String, competitionId: String, request: SetGroupMappingRequest, userId: String): Unit = dbQuery {
        val rating = requireRating(ratingId)
        requireClubAdmin(rating[RatingSeries.ownerClubId], userId)

        val ratingCompetition = RatingCompetitions.selectAll()
            .where { (RatingCompetitions.ratingId eq ratingId) and (RatingCompetitions.competitionId eq competitionId) }
            .singleOrNull() ?: throw NotFoundException("Rating competition not found")
        val ratingCompetitionId = ratingCompetition[RatingCompetitions.id]

        RatingGroupMappings.deleteWhere { RatingGroupMappings.ratingCompetitionId eq ratingCompetitionId }
        request.mappings.forEach { entry ->
            RatingGroupMappings.insert {
                it[id] = UUID.randomUUID().toString()
                it[RatingGroupMappings.ratingCompetitionId] = ratingCompetitionId
                it[participantGroupId] = entry.participantGroupId
                it[ratingGroupId] = entry.ratingGroupId
            }
        }
        Unit
    }

    suspend fun getStandings(ratingId: String, ratingGroupId: Long): RatingStandingsResponse = dbQuery {
        requireRating(ratingId)

        val ratingCompetitionRows = RatingCompetitions.selectAll()
            .where { RatingCompetitions.ratingId eq ratingId }
            .toList()

        data class Contribution(val competitionId: String, val place: Int, val points: Int)

        val contributionsByParticipant = mutableMapOf<String, MutableList<Contribution>>()
        val displayNameByParticipant = mutableMapOf<String, String>()

        ratingCompetitionRows.forEach { ratingCompetitionRow ->
            val ratingCompetitionId = ratingCompetitionRow[RatingCompetitions.id]
            val competitionId = ratingCompetitionRow[RatingCompetitions.competitionId]

            val mappedGroupIds = RatingGroupMappings.selectAll()
                .where {
                    (RatingGroupMappings.ratingCompetitionId eq ratingCompetitionId) and
                        (RatingGroupMappings.ratingGroupId eq ratingGroupId)
                }
                .map { it[RatingGroupMappings.participantGroupId] }

            if (mappedGroupIds.isEmpty()) return@forEach

            val results = OrienteeringResults.selectAll()
                .where {
                    (OrienteeringResults.competitionId eq competitionId) and
                        (OrienteeringResults.groupId inList mappedGroupIds) and
                        (OrienteeringResults.status eq "FINISHED")
                }
                .toList()

            results.forEach { resultRow ->
                val place = resultRow[OrienteeringResults.rank] ?: return@forEach
                val participant = OrienteeringParticipants.selectAll()
                    .where { OrienteeringParticipants.id eq resultRow[OrienteeringResults.participantId] }
                    .singleOrNull() ?: return@forEach

                val participantUserId = participant[OrienteeringParticipants.userId]
                val firstName = participant[OrienteeringParticipants.firstName]
                val lastName = participant[OrienteeringParticipants.lastName]
                val key = participantKey(participantUserId, firstName, lastName)

                displayNameByParticipant.putIfAbsent(key, "$firstName $lastName".trim())
                contributionsByParticipant.getOrPut(key) { mutableListOf() }
                    .add(Contribution(competitionId, place, RatingPointsTable.pointsForPlace(place)))
            }
        }

        val totals = contributionsByParticipant.mapValues { (_, contributions) -> contributions.sumOf { it.points } }
        val sortedKeys = totals.entries.sortedByDescending { it.value }.map { it.key }

        var rank = 1
        var prevTotal: Int? = null
        var skipCount = 0

        val standings = sortedKeys.mapIndexed { index, key ->
            val total = totals.getValue(key)
            if (prevTotal != null && total == prevTotal) {
                skipCount++
            } else {
                rank = index + 1 - skipCount
            }
            prevTotal = total

            RatingStandingEntry(
                participantKey = key,
                displayName = displayNameByParticipant.getValue(key),
                totalPoints = total,
                rank = rank,
                breakdown = contributionsByParticipant.getValue(key).map {
                    RatingStandingBreakdownEntry(it.competitionId, it.place, it.points)
                }
            )
        }

        RatingStandingsResponse(ratingGroupId, standings)
    }

    /** Ключ участника для сопоставления между стартами: userId, если он есть, иначе нормализованные фамилия+имя. */
    private fun participantKey(userId: String?, firstName: String, lastName: String): String =
        if (!userId.isNullOrBlank()) "user:$userId"
        else "guest:${lastName.trim().lowercase()}:${firstName.trim().lowercase()}"

    /**
     * Подсказка маппинга: если для группы соревнования уже есть сохранённый маппинг — возвращается он,
     * иначе — авто-подсказка по совпадению gender + пересечению возрастного диапазона [minAge,maxAge].
     */
    private fun suggestGroupMapping(ratingId: String, competitionId: String): List<RatingGroupMappingSuggestionResponse> {
        val ratingGroups = RatingGroups.selectAll().where { RatingGroups.ratingId eq ratingId }.toList()
        val participantGroups = ParticipantGroups.selectAll().where { ParticipantGroups.competitionId eq competitionId }.toList()

        val ratingCompetitionId = RatingCompetitions.selectAll()
            .where { (RatingCompetitions.ratingId eq ratingId) and (RatingCompetitions.competitionId eq competitionId) }
            .singleOrNull()?.get(RatingCompetitions.id)

        val existingMapping = if (ratingCompetitionId != null) {
            RatingGroupMappings.selectAll()
                .where { RatingGroupMappings.ratingCompetitionId eq ratingCompetitionId }
                .associate { it[RatingGroupMappings.participantGroupId] to it[RatingGroupMappings.ratingGroupId] }
        } else {
            emptyMap()
        }

        return participantGroups.map { pg ->
            val participantGroupId = pg[ParticipantGroups.id]
            val existingRatingGroupId = existingMapping[participantGroupId]

            if (existingRatingGroupId != null) {
                return@map RatingGroupMappingSuggestionResponse(
                    participantGroupId = participantGroupId,
                    participantGroupTitle = pg[ParticipantGroups.title],
                    suggestedRatingGroupId = existingRatingGroupId,
                    confidence = 1f
                )
            }

            val pgGender = pg[ParticipantGroups.gender]
            val pgMinAge = pg[ParticipantGroups.minAge]
            val pgMaxAge = pg[ParticipantGroups.maxAge]

            val match = ratingGroups.firstOrNull { rg ->
                val rgGender = rg[RatingGroups.gender]
                val rgMinAge = rg[RatingGroups.minAge]
                val rgMaxAge = rg[RatingGroups.maxAge]
                val genderMatches = rgGender == null || pgGender == null || rgGender == pgGender
                val ageOverlaps = ageRangesOverlap(pgMinAge, pgMaxAge, rgMinAge, rgMaxAge)
                genderMatches && ageOverlaps
            }

            RatingGroupMappingSuggestionResponse(
                participantGroupId = participantGroupId,
                participantGroupTitle = pg[ParticipantGroups.title],
                suggestedRatingGroupId = match?.get(RatingGroups.id),
                confidence = if (match != null) 1f else 0f
            )
        }
    }

    private fun ageRangesOverlap(aMin: Int?, aMax: Int?, bMin: Int?, bMax: Int?): Boolean {
        val lower = maxOf(aMin ?: Int.MIN_VALUE, bMin ?: Int.MIN_VALUE)
        val upper = minOf(aMax ?: Int.MAX_VALUE, bMax ?: Int.MAX_VALUE)
        return lower <= upper
    }

    private fun insertGroups(ratingId: String, groups: List<RatingGroupRequest>) {
        groups.forEach { group ->
            RatingGroups.insert {
                it[RatingGroups.ratingId] = ratingId
                it[title] = group.title
                it[gender] = group.gender
                it[minAge] = group.minAge
                it[maxAge] = group.maxAge
                it[orderIndex] = group.orderIndex
            }
        }
    }

    private fun loadGroups(ratingId: String): List<RatingGroupResponse> =
        RatingGroups.selectAll()
            .where { RatingGroups.ratingId eq ratingId }
            .orderBy(RatingGroups.orderIndex, SortOrder.ASC)
            .map {
                RatingGroupResponse(
                    id = it[RatingGroups.id],
                    ratingId = it[RatingGroups.ratingId],
                    title = it[RatingGroups.title],
                    gender = it[RatingGroups.gender],
                    minAge = it[RatingGroups.minAge],
                    maxAge = it[RatingGroups.maxAge],
                    orderIndex = it[RatingGroups.orderIndex]
                )
            }

    private fun requireRating(ratingId: String): ResultRow =
        RatingSeries.selectAll().where { RatingSeries.id eq ratingId }.singleOrNull()
            ?: throw NotFoundException("Rating not found")

    /** Бросает [ForbiddenException], если userId не FOUNDER/ADMIN клуба [clubId]. */
    private fun requireClubAdmin(clubId: String, userId: String) {
        val membership = ClubMembers.selectAll()
            .where { (ClubMembers.clubId eq clubId) and (ClubMembers.userId eq userId) }
            .singleOrNull() ?: throw ForbiddenException("Вы не состоите в клубе-владельце рейтинга")
        if (membership[ClubMembers.role] !in listOf("FOUNDER", "ADMIN")) {
            throw ForbiddenException("Только FOUNDER/ADMIN клуба может управлять его рейтингом")
        }
    }

    private fun buildResponse(ratingId: String): RatingResponse {
        val row = requireRating(ratingId)
        return row.toResponse(loadGroups(ratingId))
    }

    private fun ResultRow.toResponse(groups: List<RatingGroupResponse>) = RatingResponse(
        id = this[RatingSeries.id],
        name = this[RatingSeries.name],
        ownerClubId = this[RatingSeries.ownerClubId],
        groups = groups,
        createdAt = this[RatingSeries.createdAt],
        updatedAt = this[RatingSeries.updatedAt]
    )

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
