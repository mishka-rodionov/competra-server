package com.competra.data.response.orienteering

import com.google.gson.annotations.SerializedName

/**
 * Подсказки для поля «Команда» при регистрации на соревнование.
 *
 * @property options Клубные команды пользователя по виду спорта соревнования; клуб без подходящей
 * команды — вариантом с `teamId == null`.
 * @property protocolNames Подписи команд, уже встречающиеся в протоколе соревнования.
 * @property suggestedCommandName Что подставить в поле сразу (null — оставить пустым).
 */
data class RegistrationTeamOptionsResponse(
    @SerializedName("options") val options: List<RegistrationTeamOptionResponse>,
    @SerializedName("protocolNames") val protocolNames: List<String>,
    @SerializedName("suggestedCommandName") val suggestedCommandName: String?
)

/** @property label Подпись для протокола: «Клуб (Команда)» или название клуба. */
data class RegistrationTeamOptionResponse(
    @SerializedName("teamId") val teamId: String?,
    @SerializedName("clubId") val clubId: String,
    @SerializedName("clubName") val clubName: String,
    @SerializedName("teamName") val teamName: String?,
    @SerializedName("label") val label: String
)
