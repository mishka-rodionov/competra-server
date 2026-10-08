package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

/** Настройки командного зачёта в ответах соревнования; `null` в соревновании — зачёта нет. */
data class TeamScoringResponse(
    /** POINTS / TIME — способ подсчёта в группе. */
    @SerializedName("groupMethod") val groupMethod: String,
    /** N — сколько лучших результатов команды в группе идёт в зачёт. */
    @SerializedName("groupCountedResults") val groupCountedResults: Int,
    /** MEN / WOMEN / ALL — общие зачёты. */
    @SerializedName("overallScopes") val overallScopes: List<String>
)

/** Вычисленный командный зачёт соревнования (GET …/competitions/{id}/team-standings). */
data class TeamStandingsResponse(
    @SerializedName("groupMethod") val groupMethod: String,
    @SerializedName("groupCountedResults") val groupCountedResults: Int,
    /** Зачёт в каждой группе — в порядке групп соревнования; группы без команд не выводятся. */
    @SerializedName("groupStandings") val groupStandings: List<GroupTeamStandingResponse>,
    /** Общие зачёты — в порядке MEN, WOMEN, ALL; без команд не выводятся. */
    @SerializedName("overallStandings") val overallStandings: List<OverallTeamStandingResponse>
)

data class GroupTeamStandingResponse(
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("groupTitle") val groupTitle: String,
    @SerializedName("teams") val teams: List<GroupTeamResponse>
)

data class GroupTeamResponse(
    /** Место команды в группе; null — вне зачёта (по времени финишировали меньше N). */
    @SerializedName("place") val place: Int?,
    @SerializedName("teamName") val teamName: String,
    /** Сумма очков (POINTS). */
    @SerializedName("points") val points: Int?,
    /** Сумма времени в секундах (TIME); null — вне зачёта. */
    @SerializedName("timeSeconds") val timeSeconds: Long?,
    /** Все участники команды с результатом в группе — вошедшие в зачёт первыми. */
    @SerializedName("members") val members: List<TeamMemberResultResponse>
)

data class TeamMemberResultResponse(
    @SerializedName("participantId") val participantId: String,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    /** Место в группе (только у FINISHED). */
    @SerializedName("place") val place: Int?,
    @SerializedName("status") val status: String,
    @SerializedName("points") val points: Int,
    /** Время с учётом штрафа, секунды (только у FINISHED). */
    @SerializedName("timeSeconds") val timeSeconds: Long?,
    /** true — результат вошёл в зачёт команды. */
    @SerializedName("counted") val counted: Boolean
)

data class OverallTeamStandingResponse(
    /** MEN / WOMEN / ALL. */
    @SerializedName("scope") val scope: String,
    @SerializedName("teams") val teams: List<OverallTeamResponse>
)

data class OverallTeamResponse(
    @SerializedName("place") val place: Int,
    @SerializedName("teamName") val teamName: String,
    /** Сумма баллов за командные места в группах. */
    @SerializedName("points") val points: Int,
    /** Группы, где команда заняла место, — по убыванию баллов. */
    @SerializedName("groups") val groups: List<OverallTeamGroupResponse>
)

data class OverallTeamGroupResponse(
    @SerializedName("groupId") val groupId: Long,
    @SerializedName("groupTitle") val groupTitle: String,
    @SerializedName("place") val place: Int,
    @SerializedName("points") val points: Int
)
