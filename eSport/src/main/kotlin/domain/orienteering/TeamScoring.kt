package com.competra.domain.orienteering

/** Способ подсчёта командного зачёта в группе. */
enum class TeamScoringMethod {
    /** Сумма очков за места N лучших участников команды (RatingPointsTable). */
    POINTS,

    /** Сумма времени N лучших участников команды; финишировавших меньше N — вне зачёта. */
    TIME;

    companion object {
        fun fromString(raw: String?): TeamScoringMethod = entries.firstOrNull { it.name == raw } ?: POINTS
    }
}

/** Общий командный зачёт — по полу группы или по всем группам. */
enum class TeamOverallScope {
    MEN, WOMEN, ALL;

    companion object {
        fun fromString(raw: String?): TeamOverallScope? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Настройки командного зачёта соревнования (docs/specs/team-scoring.md в competra-android).
 *
 * Зачёт задаётся абстрактно, без ссылок на группы: в каждой группе считается свой зачёт
 * ([groupMethod], [groupCountedResults]), общие зачёты ([overallScopes]) складывают баллы
 * за командные места в группах. Хранится JSON-ом в `orienteering_competitions.team_scoring`;
 * `null` — командного зачёта нет.
 */
data class TeamScoring(
    val groupMethod: TeamScoringMethod = TeamScoringMethod.POINTS,
    val groupCountedResults: Int = DEFAULT_COUNTED_RESULTS,
    val overallScopes: List<TeamOverallScope> = emptyList()
) {
    companion object {
        const val DEFAULT_COUNTED_RESULTS = 3
    }
}
