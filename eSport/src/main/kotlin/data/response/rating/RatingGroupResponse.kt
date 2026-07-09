package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

data class RatingGroupResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("ratingId") val ratingId: String,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("orderIndex") val orderIndex: Int
)
