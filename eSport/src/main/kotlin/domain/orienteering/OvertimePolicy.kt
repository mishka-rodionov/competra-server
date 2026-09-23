package com.competra.domain.orienteering

/**
 * Что делать с результатом, превысившим контрольное время (КВ).
 *
 * Политика задаётся один раз на всё соревнование ([OrienteeringCompetitions.overtimePolicy]),
 * само КВ — на уровне соревнования с переопределением на уровне группы
 * (`group.timeLimitMinutes ?: competition.controlTimeMinutes`).
 */
enum class OvertimePolicy {
    /** КВ показывается в информации о соревновании, но на результаты не влияет. */
    IGNORE,

    /** Превысившие КВ снимаются: статус OVERTIME, места не получают. */
    DISQUALIFY,

    /**
     * Формат "по выбору" (score-О): штраф очками за каждую минуту опоздания
     * (`scorePenaltyPerMinute`) и обнуление после `maxLatenessMinutes`.
     * Штраф считает клиент при считывании чипа, сервер результат не снимает.
     */
    SCORE_PENALTY;

    companion object {
        val DEFAULT = IGNORE

        fun fromString(raw: String?): OvertimePolicy =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
