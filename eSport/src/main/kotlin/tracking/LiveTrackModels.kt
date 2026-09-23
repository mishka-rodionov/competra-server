package com.competra.tracking

import com.google.gson.annotations.SerializedName

/** Состояние сессии онлайн-трекинга. */
enum class LiveTrackStatus {
    /** Бегун на дистанции, точки принимаются. */
    ACTIVE,

    /** Закрыта по появлению результата участника. */
    FINISHED,

    /** Остановлена бегуном вручную. */
    STOPPED,

    /** Закрыта сторожем: превышен срок или давно нет точек. */
    TIMED_OUT
}

/** Причина закрытия сессии. */
enum class CloseReason {
    /** Бегун нажал «Стоп». */
    MANUAL,

    /** У участника сохранён финальный результат. */
    RESULT_SAVED,

    /** Превышено контрольное время (или лимит по умолчанию) с запасом. */
    CONTROL_TIME,

    /** Давно не приходило ни одной точки. */
    INACTIVITY
}

/**
 * Сессия онлайн-трекинга участника. Имя/группа/дистанция денормализованы из основной БД в момент
 * старта — после этого трекинг в основную БД не ходит.
 *
 * @property id UUID сессии.
 * @property competitionId Соревнование.
 * @property distanceId Дистанция участника (через его группу).
 * @property distanceName Название дистанции на момент старта.
 * @property participantId Участник.
 * @property userId Пользователь, которому принадлежит участник (владелец сессии).
 * @property displayName «Фамилия Имя» участника.
 * @property groupName Название группы.
 * @property startNumber Стартовый номер.
 * @property status Состояние.
 * @property closeReason Причина закрытия или `null`, пока сессия активна.
 * @property startedAt Когда бегун включил трекинг (Unix ms); база для `TrackCodec`.
 * @property closedAt Когда сессия закрыта (Unix ms).
 * @property lastPointAt Время последней принятой точки (время фикса, Unix ms).
 * @property deadlineAt Крайний срок, после которого сторож закроет сессию (Unix ms).
 * @property lastBatchSeq Номер последнего принятого батча (идемпотентность повторов).
 * @property consentAt Когда бегун согласился на публикацию трека (Unix ms).
 */
data class LiveTrackSession(
    val id: String,
    val competitionId: String,
    val distanceId: Long,
    val distanceName: String?,
    val participantId: String,
    val userId: String,
    val displayName: String,
    val groupName: String?,
    val startNumber: Int?,
    val status: LiveTrackStatus,
    val closeReason: CloseReason?,
    val startedAt: Long,
    val closedAt: Long?,
    val lastPointAt: Long?,
    val deadlineAt: Long,
    val lastBatchSeq: Int,
    val consentAt: Long
)

/**
 * GPS-точка трека.
 *
 * @property t Время фикса (Unix ms).
 * @property lat Широта WGS84.
 * @property lon Долгота WGS84.
 * @property accuracy Точность фикса в метрах, если известна.
 */
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float? = null
)

/** Закрытая (или активная) сессия вместе с треком в формате `TrackCodec`. */
data class ArchivedSession(
    val session: LiveTrackSession,
    val trackEncoded: String?
)

// ---------- Запросы ----------

/**
 * `POST /api/live-track/sessions` — старт или возобновление трекинга.
 *
 * @property competitionId Соревнование.
 * @property participantId Участник, от имени которого пишется трек.
 * @property consent Согласие на публикацию трека (обязательно `true`).
 */
data class StartSessionRequest(
    @SerializedName("competitionId") val competitionId: String?,
    @SerializedName("participantId") val participantId: String?,
    @SerializedName("consent") val consent: Boolean?
)

/**
 * `POST /api/live-track/sessions/{id}/points` — батч точек.
 *
 * Клиент отправляет батчи **последовательно** (следующий — только после ответа на предыдущий):
 * батч с `batchSeq` не больше уже принятого считается повтором и игнорируется.
 *
 * @property batchSeq Номер батча, строго возрастает начиная с 1.
 * @property points Точки батча.
 */
data class PointsBatchRequest(
    @SerializedName("batchSeq") val batchSeq: Int?,
    @SerializedName("points") val points: List<PointRequest>?
)

/**
 * Точка в батче.
 *
 * @property t Время фикса (Unix ms).
 * @property lat Широта.
 * @property lon Долгота.
 * @property acc Точность в метрах.
 */
data class PointRequest(
    @SerializedName("t") val t: Long?,
    @SerializedName("lat") val lat: Double?,
    @SerializedName("lon") val lon: Double?,
    @SerializedName("acc") val acc: Float?
)

// ---------- Ответы ----------

/** Ответ на старт/возобновление сессии. */
data class SessionResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("status") val status: LiveTrackStatus,
    @SerializedName("closeReason") val closeReason: CloseReason?,
    @SerializedName("lastBatchSeq") val lastBatchSeq: Int,
    @SerializedName("uploadIntervalSec") val uploadIntervalSec: Int,
    @SerializedName("deadlineAt") val deadlineAt: Long
)

/** Ответ на батч точек / стоп: текущее состояние сессии и подтверждённый номер батча. */
data class PointsAckResponse(
    @SerializedName("status") val status: LiveTrackStatus,
    @SerializedName("closeReason") val closeReason: CloseReason?,
    @SerializedName("ackedBatchSeq") val ackedBatchSeq: Int
)

/** Дистанция соревнования, по которой есть (или были) онлайн-треки. */
data class TrackedDistanceResponse(
    @SerializedName("distanceId") val distanceId: Long,
    @SerializedName("name") val name: String?,
    @SerializedName("activeCount") val activeCount: Int,
    @SerializedName("totalCount") val totalCount: Int
)

/**
 * Ответ `live`: новые точки после курсора и метаданные **всех** сессий дистанции (их мало, а так
 * клиент всегда видит смену статуса и `lastPointAt` без отдельного канала событий).
 *
 * @property cursor Непрозрачный курсор для следующего запроса.
 * @property reset `true` — курсор клиента недействителен (перезапуск процесса): отбросить
 *   накопленные точки и заменить их пришедшими (в ответе полные треки).
 * @property serverTime Время сервера (Unix ms) — для «нет данных N мин» без доверия часам клиента.
 */
data class LiveSnapshotResponse(
    @SerializedName("cursor") val cursor: String,
    @SerializedName("reset") val reset: Boolean,
    @SerializedName("serverTime") val serverTime: Long,
    @SerializedName("sessions") val sessions: List<LiveSessionResponse>
)

/**
 * Сессия в ответе `live`.
 *
 * @property points Новые точки `[t, lat, lon]`. Приходят в порядке приёма — досланные после
 *   потери связи точки могут быть «старше» уже показанных, клиент сортирует по `t`.
 */
data class LiveSessionResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("groupName") val groupName: String?,
    @SerializedName("startNumber") val startNumber: Int?,
    @SerializedName("status") val status: LiveTrackStatus,
    @SerializedName("closeReason") val closeReason: CloseReason?,
    @SerializedName("startedAt") val startedAt: Long,
    @SerializedName("lastPointAt") val lastPointAt: Long?,
    @SerializedName("points") val points: List<List<Number>>
)

/** Трек в ответе `tracks` (архив): `trackEncoded` декодируется `TrackCodec` от `startedAt`. */
data class ArchivedTrackResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("groupName") val groupName: String?,
    @SerializedName("startNumber") val startNumber: Int?,
    @SerializedName("status") val status: LiveTrackStatus,
    @SerializedName("closeReason") val closeReason: CloseReason?,
    @SerializedName("startedAt") val startedAt: Long,
    @SerializedName("closedAt") val closedAt: Long?,
    @SerializedName("trackEncoded") val trackEncoded: String
)
