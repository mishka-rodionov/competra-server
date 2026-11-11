package com.competra.domain.orienteering

/**
 * Формат дистанции: направление (`FORWARD` / `BY_CHOICE` / `MARKING`) и режим «по выбору».
 *
 * У соревнования формат по умолчанию ([com.competra.data.database.entity.OrienteeringCompetitions.direction],
 * [com.competra.data.database.entity.OrienteeringCompetitions.byChoiceMode]); дистанция может его
 * переопределить ([com.competra.data.database.entity.Distances.direction] /
 * [com.competra.data.database.entity.Distances.byChoiceMode], null — «как у соревнования»).
 * Спека — competra-android/docs/specs/per-distance-format.md.
 */
data class CourseFormat(val direction: String, val byChoiceMode: String) {
    /** true, если места на дистанции считаются по сумме баллов (score-О). */
    val ranksByScore: Boolean get() = ranksByScore(direction, byChoiceMode)

    /**
     * Одинаковые ли правила у двух форматов: режим «по выбору» сравнивается только при BY_CHOICE
     * (у остальных направлений он не используется).
     */
    fun sameAs(other: CourseFormat): Boolean =
        direction == other.direction &&
            (direction != OrienteeringDirection.BY_CHOICE.name || byChoiceMode == other.byChoiceMode)
}

/**
 * Значение в запросе дистанции, означающее «как у соревнования» (в БД — null). Сам null в запросе
 * значит «не менять»: старые клиенты поле не знают и иначе сбросили бы формат дистанции.
 */
const val INHERIT_FORMAT = "INHERIT"

/** Эффективный формат дистанции: свои значения дистанции, иначе — соревнования. */
fun effectiveCourseFormat(
    distanceDirection: String?,
    distanceByChoiceMode: String?,
    competitionDirection: String?,
    competitionByChoiceMode: String?
): CourseFormat = CourseFormat(
    direction = distanceDirection ?: competitionDirection ?: OrienteeringDirection.FORWARD.name,
    byChoiceMode = ByChoiceMode.fromString(distanceByChoiceMode ?: competitionByChoiceMode).name
)

/** Нормализует направление дистанции из запроса: null — не менять, [INHERIT_FORMAT] — сбросить в null. */
fun requestedDistanceDirection(raw: String?): RequestedValue = when {
    raw == null -> RequestedValue.Keep
    raw == INHERIT_FORMAT -> RequestedValue.Set(null)
    else -> RequestedValue.Set(
        OrienteeringDirection.entries.firstOrNull { it.name == raw }?.name
            ?: OrienteeringDirection.FORWARD.name
    )
}

/** Нормализует режим «по выбору» дистанции из запроса — см. [requestedDistanceDirection]. */
fun requestedDistanceByChoiceMode(raw: String?): RequestedValue = when {
    raw == null -> RequestedValue.Keep
    raw == INHERIT_FORMAT -> RequestedValue.Set(null)
    else -> RequestedValue.Set(ByChoiceMode.fromString(raw).name)
}

/** Что сделать с nullable-полем по запросу: не трогать или записать значение (в т.ч. null). */
sealed interface RequestedValue {
    data object Keep : RequestedValue
    data class Set(val value: String?) : RequestedValue
}

/**
 * Политики превышения КВ соревнования — отдельно для дистанций, где места считаются по времени, и
 * для дистанций с баллами.
 *
 * @property time для FORWARD / MARKING / BY_CHOICE + MIN_CONTROLS: [OvertimePolicy.IGNORE] или [OvertimePolicy.DISQUALIFY]
 * @property score для BY_CHOICE + SCORE: любая политика, по умолчанию [OvertimePolicy.SCORE_PENALTY]
 */
data class OvertimePolicies(val time: OvertimePolicy, val score: OvertimePolicy) {

    /** Политика для дистанции заданного вида. */
    fun forCourse(ranksByScore: Boolean): OvertimePolicy = if (ranksByScore) score else time

    /**
     * Значение legacy-поля `overtimePolicy` — политика для формата соревнования по умолчанию.
     * Его читают клиенты, не знающие о формате на дистанции.
     */
    fun legacy(competitionRanksByScore: Boolean): OvertimePolicy = forCourse(competitionRanksByScore)

    companion object {
        /**
         * Разбирает сохранённые/присланные значения. Если новых полей нет (соревнование до фичи или
         * старый клиент), политика для формата по умолчанию берётся из legacy-поля, а для другого
         * вида — умолчание (у баллов — штраф очками, у времени — справочное КВ).
         */
        fun resolve(
            legacy: String?,
            time: String?,
            score: String?,
            competitionRanksByScore: Boolean
        ): OvertimePolicies {
            val legacyPolicy = legacy?.let { OvertimePolicy.fromString(it) }
            val timePolicy = time?.let { OvertimePolicy.fromString(it) }
                ?: legacyPolicy.takeIf { !competitionRanksByScore }
                ?: OvertimePolicy.IGNORE
            val scorePolicy = score?.let { OvertimePolicy.fromString(it) }
                ?: legacyPolicy.takeIf { competitionRanksByScore }
                ?: OvertimePolicy.SCORE_PENALTY
            return OvertimePolicies(time = sanitizeTime(timePolicy), score = scorePolicy)
        }

        /** Штраф очками для дистанций по времени не имеет смысла — там очков нет. */
        private fun sanitizeTime(policy: OvertimePolicy): OvertimePolicy =
            if (policy == OvertimePolicy.SCORE_PENALTY) OvertimePolicy.IGNORE else policy
    }
}
