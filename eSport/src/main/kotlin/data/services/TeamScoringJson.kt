package com.competra.data.services

import com.competra.data.requests.orienteering.TeamScoringRequest
import com.competra.data.response.orienteering.TeamScoringResponse
import com.competra.domain.orienteering.TeamOverallScope
import com.competra.domain.orienteering.TeamScoring
import com.competra.domain.orienteering.TeamScoringMethod
import com.google.gson.Gson

/** Хранение настроек командного зачёта JSON-ом в `orienteering_competitions.team_scoring`. */
internal object TeamScoringJson {

    private val gson = Gson()

    private data class Stored(
        val groupMethod: String? = null,
        val groupCountedResults: Int? = null,
        val overallScopes: List<String>? = null
    )

    /** Битое или пустое значение — зачёта нет, а не ошибка запроса. */
    fun parse(json: String?): TeamScoring? = json?.takeIf { it.isNotBlank() }?.let {
        runCatching { gson.fromJson(it, Stored::class.java) }.getOrNull()?.let { stored ->
            TeamScoring(
                groupMethod = TeamScoringMethod.fromString(stored.groupMethod),
                groupCountedResults = stored.groupCountedResults?.takeIf { n -> n > 0 } ?: TeamScoring.DEFAULT_COUNTED_RESULTS,
                overallScopes = stored.overallScopes.orEmpty().mapNotNull(TeamOverallScope::fromString).distinct()
            )
        }
    }

    fun write(settings: TeamScoring?): String? = settings?.let {
        gson.toJson(Stored(it.groupMethod.name, it.groupCountedResults, it.overallScopes.map(TeamOverallScope::name)))
    }

    /** Новое значение колонки по запросу: `null` — не менять, `enabled = false` — выключить. */
    fun fromRequest(request: TeamScoringRequest?, current: String?): String? = when {
        request == null -> current
        !request.enabled -> null
        else -> write(
            TeamScoring(
                groupMethod = TeamScoringMethod.fromString(request.groupMethod),
                groupCountedResults = request.groupCountedResults?.takeIf { it > 0 } ?: TeamScoring.DEFAULT_COUNTED_RESULTS,
                overallScopes = request.overallScopes.orEmpty().mapNotNull(TeamOverallScope::fromString).distinct()
            )
        )
    }

    fun toResponse(json: String?): TeamScoringResponse? = parse(json)?.let {
        TeamScoringResponse(it.groupMethod.name, it.groupCountedResults, it.overallScopes.map(TeamOverallScope::name))
    }
}
