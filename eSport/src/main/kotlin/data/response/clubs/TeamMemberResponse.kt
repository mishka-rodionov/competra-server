package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

data class TeamMemberResponse(
    @SerializedName("id") val id: String,
    @SerializedName("teamId") val teamId: String,
    @SerializedName("clubMemberId") val clubMemberId: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("firstName") val firstName: String,
    @SerializedName("lastName") val lastName: String,
    @SerializedName("role") val role: String,
    @SerializedName("joinedAt") val joinedAt: Long
)
