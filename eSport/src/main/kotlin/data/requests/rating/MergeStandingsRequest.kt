package com.competra.data.requests.rating

import com.google.gson.annotations.SerializedName

/**
 * Объединить строки таблицы рейтинга: участников строк [sourceKeys] привязать к аккаунту строки
 * [targetKey] (если она — аккаунт) или переименовать в её ФИО (если обе строки без аккаунта).
 */
data class MergeStandingsRequest(
    @SerializedName("ratingGroupId") val ratingGroupId: Long,
    @SerializedName("targetKey") val targetKey: String,
    @SerializedName("sourceKeys") val sourceKeys: List<String>
)
