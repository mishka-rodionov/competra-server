package com.competra.data.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.competra.data.database.entity.Distances
import com.competra.data.exception.ConflictException
import com.competra.data.exception.ForbiddenException
import com.competra.data.requests.orienteering.ControlPointRequest
import com.competra.data.requests.orienteering.DistanceRequest
import com.competra.data.response.orienteering.ControlPointResponse
import com.competra.data.response.orienteering.DistanceResponse
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.competra.data.database.ioTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class DistanceService(private val uploadService: UploadService) {

    private val log = LoggerFactory.getLogger(DistanceService::class.java)

    private val gson = Gson()
    private val cpListType = object : TypeToken<List<ControlPointResponse>>() {}.type

    private fun serializeControlPoints(points: List<ControlPointRequest>): String =
        gson.toJson(points)

    private fun deserializeControlPoints(json: String?): List<ControlPointResponse> =
        if (json.isNullOrBlank()) emptyList()
        else gson.fromJson(json, cpListType) ?: emptyList()

    suspend fun upsertAll(requests: List<DistanceRequest>, userId: String): List<DistanceResponse> {
        val replacedMapUrls = mutableSetOf<String>()
        val responses = dbQuery {
            val saved = upsertAllInTransaction(requests, userId, replacedMapUrls)
            // Один файл карты может висеть на нескольких дистанциях — удаляем только тот,
            // на который после сохранения не ссылается уже никто.
            replacedMapUrls.retainAll { url -> Distances.selectAll().where { Distances.mapUrl eq url }.empty() }
            saved
        }
        // После коммита: старая ссылка карты публичная, и пока файл лежит в бакете, она продолжает
        // открывать карту у всех, кто её успел получить. Ошибка S3 сохранение не откатывает.
        replacedMapUrls.forEach { url ->
            try {
                withContext(Dispatchers.IO) { uploadService.deleteDistanceMap(url) }
            } catch (e: Exception) {
                log.warn("Не удалось удалить заменённую карту дистанции $url", e)
            }
        }
        return responses
    }

    private fun upsertAllInTransaction(
        requests: List<DistanceRequest>,
        userId: String,
        replacedMapUrls: MutableSet<String>
    ): List<DistanceResponse> {
        val now = System.currentTimeMillis()
        return requests.map { req ->
            val cpJson = serializeControlPoints(req.controlPoints)

            if (req.distanceId != null) {
                val existing = Distances.selectAll()
                    .where { Distances.id eq req.distanceId }
                    .singleOrNull()

                val serverTs = existing?.get(Distances.updatedAt) ?: 0L
                if (existing != null && req.serverUpdatedAt != null && req.serverUpdatedAt > 0L &&
                    req.serverUpdatedAt < serverTs
                ) {
                    throw ConflictException(existing.toResponse(), req.serverUpdatedAt, serverTs)
                }

                if (existing != null) {
                    if (existing[Distances.competitionId] != req.competitionId) {
                        throw ForbiddenException("Дистанция принадлежит другому соревнованию")
                    }
                    requireDistanceEditAccess(req.competitionId, userId)
                    val oldMapUrl = existing[Distances.mapUrl]
                    if (req.mapUrl != null && oldMapUrl != null && oldMapUrl != req.mapUrl) {
                        replacedMapUrls += oldMapUrl
                    }
                    val mergedCpJson = serializeControlPoints(
                        keepStoredCoordinates(req.controlPoints, deserializeControlPoints(existing[Distances.controlPoints]))
                    )
                    Distances.update({ Distances.id eq req.distanceId }) {
                        it[competitionId] = req.competitionId
                        it[name] = req.name
                        it[lengthMeters] = req.lengthMeters
                        it[climbMeters] = req.climbMeters
                        it[controlsCount] = req.controlsCount
                        it[description] = req.description
                        it[controlPoints] = mergedCpJson
                        it[finishControlPoint] = req.finishControlPoint
                        it[startControlPoint] = req.startControlPoint
                        // Координаты старта/финиша — как и карта, только если клиент их прислал:
                        // формы редактирования и старые версии Android их не знают и затёрли бы
                        // координаты, пришедшие из IOF XML.
                        if (req.startLatitude != null && req.startLongitude != null) {
                            it[startLatitude] = req.startLatitude
                            it[startLongitude] = req.startLongitude
                        }
                        if (req.finishLatitude != null && req.finishLongitude != null) {
                            it[finishLatitude] = req.finishLatitude
                            it[finishLongitude] = req.finishLongitude
                        }
                        // Минимум КП — тоже только если прислан: null значит «клиент поле не знает».
                        if (req.minControlsCount != null) {
                            it[minControlsCount] = storedMinControlsCount(req.minControlsCount)
                        }
                        // Карту и её привязку обновляем только если клиент её прислал. Android-клиент
                        // и формы редактирования дистанции поля карты не шлют — без этой проверки
                        // любое их сохранение стирало карту, прикреплённую через веб. Открепления
                        // карты в клиентах нет, поэтому mapUrl == null всегда значит «не трогать».
                        if (req.mapUrl != null) {
                            it[mapUrl] = req.mapUrl
                            it[mapTopLeftLat] = req.mapTopLeftLat
                            it[mapTopLeftLng] = req.mapTopLeftLng
                            it[mapTopRightLat] = req.mapTopRightLat
                            it[mapTopRightLng] = req.mapTopRightLng
                            it[mapBottomRightLat] = req.mapBottomRightLat
                            it[mapBottomRightLng] = req.mapBottomRightLng
                        }
                        it[updatedAt] = now
                    }
                    return@map Distances.selectAll().where { Distances.id eq req.distanceId }.single().toResponse()
                }
            }

            requireDistanceEditAccess(req.competitionId, userId)
            val newId = Distances.insert {
                it[competitionId] = req.competitionId
                it[name] = req.name
                it[lengthMeters] = req.lengthMeters
                it[climbMeters] = req.climbMeters
                it[controlsCount] = req.controlsCount
                it[description] = req.description
                it[controlPoints] = cpJson
                it[finishControlPoint] = req.finishControlPoint
                it[startControlPoint] = req.startControlPoint
                it[startLatitude] = req.startLatitude
                it[startLongitude] = req.startLongitude
                it[finishLatitude] = req.finishLatitude
                it[finishLongitude] = req.finishLongitude
                it[minControlsCount] = req.minControlsCount?.let(::storedMinControlsCount)
                it[mapUrl] = req.mapUrl
                it[mapTopLeftLat] = req.mapTopLeftLat
                it[mapTopLeftLng] = req.mapTopLeftLng
                it[mapTopRightLat] = req.mapTopRightLat
                it[mapTopRightLng] = req.mapTopRightLng
                it[mapBottomRightLat] = req.mapBottomRightLat
                it[mapBottomRightLng] = req.mapBottomRightLng
                it[updatedAt] = now
            } get Distances.id

            Distances.selectAll().where { Distances.id eq newId }.single().toResponse()
        }
    }

    /**
     * Дистанции соревнования для просмотра. Организаторы ([isCompetitionOrganizer]) видят всё
     * всегда, остальные (в т.ч. аноним, viewerUserId == null) — только после старта
     * ([isCompetitionStarted]); до старта им не отдаются карта и любые координаты, по которым
     * дистанцию можно восстановить. Организаторов берём шире, чем [requireDistanceEditAccess]:
     * редактор, получивший урезанную дистанцию, затёр бы данные при сохранении.
     */
    suspend fun getByCompetition(competitionId: String, viewerUserId: String?): List<DistanceResponse> = dbQuery {
        val distances = Distances.selectAll()
            .where { Distances.competitionId eq competitionId }
            .map { it.toResponse() }
        val canSeeCourse = isCompetitionStarted(competitionId) ||
            (viewerUserId != null && isCompetitionOrganizer(competitionId, viewerUserId))
        if (canSeeCourse) distances else distances.map { it.withoutCourse() }
    }

    suspend fun deleteById(id: Long, userId: String): Boolean = dbQuery {
        val existing = Distances.selectAll().where { Distances.id eq id }.singleOrNull()
            ?: return@dbQuery false
        requireDistanceEditAccess(existing[Distances.competitionId], userId)
        @Suppress("DEPRECATION")
        Distances.deleteWhere { Distances.id eq id } > 0
    }

    private fun ResultRow.toResponse() = DistanceResponse(
        id = this[Distances.id],
        competitionId = this[Distances.competitionId],
        name = this[Distances.name],
        lengthMeters = this[Distances.lengthMeters],
        climbMeters = this[Distances.climbMeters],
        controlsCount = this[Distances.controlsCount],
        description = this[Distances.description],
        controlPoints = deserializeControlPoints(this[Distances.controlPoints]),
        finishControlPoint = this[Distances.finishControlPoint],
        startControlPoint = this[Distances.startControlPoint],
        startLatitude = this[Distances.startLatitude],
        startLongitude = this[Distances.startLongitude],
        finishLatitude = this[Distances.finishLatitude],
        finishLongitude = this[Distances.finishLongitude],
        minControlsCount = this[Distances.minControlsCount],
        mapUrl = this[Distances.mapUrl],
        mapTopLeftLat = this[Distances.mapTopLeftLat],
        mapTopLeftLng = this[Distances.mapTopLeftLng],
        mapTopRightLat = this[Distances.mapTopRightLat],
        mapTopRightLng = this[Distances.mapTopRightLng],
        mapBottomRightLat = this[Distances.mapBottomRightLat],
        mapBottomRightLng = this[Distances.mapBottomRightLng],
        hasMap = this[Distances.mapUrl] != null,
        updatedAt = this[Distances.updatedAt]
    )

    /** Дистанция без карты и координат — то, что видит не-организатор до старта. */
    private fun DistanceResponse.withoutCourse() = copy(
        controlPoints = controlPoints.map { it.copy(latitude = null, longitude = null) },
        startLatitude = null,
        startLongitude = null,
        finishLatitude = null,
        finishLongitude = null,
        mapUrl = null,
        mapTopLeftLat = null,
        mapTopLeftLng = null,
        mapTopRightLat = null,
        mapTopRightLng = null,
        mapBottomRightLat = null,
        mapBottomRightLng = null
    )

    /**
     * КП без координат в запросе берут сохранённые координаты КП с тем же номером — как и
     * старт/финиш, null значит «не трогать». Страховка от клиента, который сохраняет дистанцию,
     * загруженную до старта в урезанном виде (без координат), и иначе стёр бы координаты из IOF XML.
     */
    private fun keepStoredCoordinates(
        requested: List<ControlPointRequest>,
        stored: List<ControlPointResponse>
    ): List<ControlPointRequest> {
        val storedByNumber = stored.associateBy { it.number }
        return requested.map { cp ->
            val storedCp = storedByNumber[cp.number]
            if (cp.latitude == null && cp.longitude == null && storedCp != null) {
                cp.copy(latitude = storedCp.latitude, longitude = storedCp.longitude)
            } else {
                cp
            }
        }
    }

    /** В запросе 0 (и любое неположительное) значит «взять все КП» — в БД это null. */
    private fun storedMinControlsCount(requested: Int): Int? = requested.takeIf { it > 0 }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        ioTransaction { block() }
}
