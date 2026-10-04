package com.competra.data.response.clubs

import com.google.gson.annotations.SerializedName

/** Клуб, совпавший по названию с введённой подписью команды, в котором пользователь ещё не состоит. */
data class ClubMatchResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("allowJoinRequests") val allowJoinRequests: Boolean
)
