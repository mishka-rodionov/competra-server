package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingStandingBreakdownEntry(
    @SerializedName("competitionId") val competitionId: String,
    @SerializedName("place") val place: Int?,
    @SerializedName("points") val points: Int,
    /** Запись участника в протоколе этого соревнования. */
    @SerializedName("participantId") val participantId: String
)

data class RatingStandingEntry(
    @SerializedName("participantKey") val participantKey: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("totalPoints") val totalPoints: Int,
    @SerializedName("rank") val rank: Int,
    @SerializedName("breakdown") val breakdown: List<RatingStandingBreakdownEntry>,
    /** Аккаунт, если строка собрана по привязанным участникам; null — ручные записи без аккаунта. */
    @SerializedName("userId") val userId: String?,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String
)

data class RatingStandingsResponse(
    @SerializedName("ratingGroupId") val ratingGroupId: Long,
    @SerializedName("standings") val standings: List<RatingStandingEntry>,
    /** Группы participantKey, похожие на одного человека: та же фамилия и совместимое имя («М.» ~ «Михаил»). */
    @SerializedName("possibleDuplicates") val possibleDuplicates: List<List<String>>
)
