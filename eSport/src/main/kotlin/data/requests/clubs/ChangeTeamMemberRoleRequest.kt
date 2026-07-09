package com.competra.data.requests.clubs

import com.google.gson.annotations.SerializedName

/** role: "CAPTAIN" | "MEMBER" */
data class ChangeTeamMemberRoleRequest(
    @SerializedName("role") val role: String
)
