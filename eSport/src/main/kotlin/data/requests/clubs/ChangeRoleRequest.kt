package com.competra.data.requests.clubs

import com.google.gson.annotations.SerializedName

/** role: "ADMIN" | "MEMBER" — назначение/снятие ADMIN, либо "FOUNDER" — передача роли основателя. */
data class ChangeRoleRequest(
    @SerializedName("role") val role: String
)
