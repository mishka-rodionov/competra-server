package com.competra.data.requests.clubs

import com.google.gson.annotations.SerializedName

data class AddTeamMemberRequest(
    @SerializedName("clubMemberId") val clubMemberId: String,
    @SerializedName("role") val role: String = "MEMBER"
)
