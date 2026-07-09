package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

data class ClubJoinRequestResponse(
    @SerializedName("id") val id: String,
    @SerializedName("clubId") val clubId: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("status") val status: String,
    @SerializedName("createdAt") val createdAt: Long
)
