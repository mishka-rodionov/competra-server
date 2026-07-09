package com.competra.data.requests.rating

import com.google.gson.annotations.SerializedName

data class RatingGroupRequest(
    @SerializedName("id") val id: Long?,
    @SerializedName("title") val title: String,
    @SerializedName("gender") val gender: String?,
    @SerializedName("minAge") val minAge: Int?,
    @SerializedName("maxAge") val maxAge: Int?,
    @SerializedName("orderIndex") val orderIndex: Int
)
