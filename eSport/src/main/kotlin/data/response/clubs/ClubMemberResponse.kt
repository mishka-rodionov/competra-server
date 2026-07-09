package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

data class ClubMemberResponse(
    @SerializedName("id") val id: String,
    @SerializedName("clubId") val clubId: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("role") val role: String,
    @SerializedName("joinedAt") val joinedAt: Long
)
