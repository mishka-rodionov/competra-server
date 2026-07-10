package com.competra.data.response.rating

import com.google.gson.annotations.SerializedName

/** Лёгкая карточка рейтинга для глобального поиска — без списка групп, они нужны только в деталях. */
data class RatingSearchResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("ownerClubId") val ownerClubId: String,
    @SerializedName("ownerClubName") val ownerClubName: String,
    @SerializedName("createdAt") val createdAt: Long
)
