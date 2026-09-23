package com.competra.data.events

import com.google.gson.annotations.SerializedName

/** Имена в RabbitMQ для событий основного приложения. Общие для публикатора и потребителей. */
object CompetraEvents {
    /** Topic-exchange событий основного приложения. */
    const val EXCHANGE = "competra.events"

    /** Routing key: результат участника сохранён (создан или изменён). */
    const val RESULT_SAVED = "result.saved"
}

/**
 * Событие «результат участника сохранён». Публикуется основным приложением после коммита
 * результата; процесс трекинга по нему закрывает онлайн-трек финишировавшего участника.
 *
 * @property competitionId Идентификатор соревнования.
 * @property participantId Идентификатор участника.
 * @property resultStatus Статус результата (`FINISHED`, `DSQ`, `DNS`, `DNF`, `OVERTIME`, `STARTED`, …).
 * @property finishTime Время финиша (Unix ms) или `null`.
 * @property savedAt Когда результат сохранён (Unix ms).
 */
data class ResultSavedEvent(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("participantId") val participantId: String,
    @SerializedName("resultStatus") val resultStatus: String,
    @SerializedName("finishTime") val finishTime: Long?,
    @SerializedName("savedAt") val savedAt: Long
)
