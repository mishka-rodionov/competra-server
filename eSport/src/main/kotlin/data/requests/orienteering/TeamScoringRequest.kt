package com.competra.data.requests.orienteering

import com.google.gson.annotations.SerializedName

/**
 * Настройки командного зачёта в запросе соревнования. Само поле `teamScoring` в запросе
 * необязательно: `null` — не менять (старые клиенты его не знают), `enabled = false` — выключить.
 */
data class TeamScoringRequest(
    @SerializedName("enabled") val enabled: Boolean,
    /** POINTS / TIME — способ подсчёта в группе. */
    @SerializedName("groupMethod") val groupMethod: String? = null,
    /** N — сколько лучших результатов команды в группе идёт в зачёт. */
    @SerializedName("groupCountedResults") val groupCountedResults: Int? = null,
    /** MEN / WOMEN / ALL — общие зачёты. */
    @SerializedName("overallScopes") val overallScopes: List<String>? = null
)
