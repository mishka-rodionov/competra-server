package com.competra.domain.orienteering

/**
 * Как подводится итог в формате «по выбору» (direction = BY_CHOICE).
 *
 * Хранится в [com.competra.data.database.entity.OrienteeringCompetitions.byChoiceMode];
 * для остальных направлений не используется.
 */
enum class ByChoiceMode {
    /** Score-О: у КП есть стоимость, места по сумме баллов (убывание), тай-брейк по времени. */
    SCORE,

    /**
     * Свободный порядок с минимумом КП: участник должен взять не меньше
     * `distances.min_controls_count` КП (null — все) и все обязательные КП, места — по времени.
     * Снятие за недобор КП выставляет клиент при считывании чипа.
     */
    MIN_CONTROLS;

    companion object {
        val DEFAULT = SCORE

        fun fromString(raw: String?): ByChoiceMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** true, если места в соревновании считаются по сумме баллов (score-О), а не по времени. */
fun ranksByScore(direction: String?, byChoiceMode: String?): Boolean =
    direction == "BY_CHOICE" && ByChoiceMode.fromString(byChoiceMode) == ByChoiceMode.SCORE
