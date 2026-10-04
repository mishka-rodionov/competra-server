package com.competra.data.services

/** Максимальная длина подписи команды — совпадает с varchar(200) колонки orienteering_participants.command_name. */
internal const val COMMAND_NAME_MAX_LENGTH = 200

private val WHITESPACE = Regex("\\s+")

/** «Клуб (Команда)» — жадный и ленивый варианты, чтобы пережить скобки внутри названий. */
private val LABEL_WITH_TEAM_GREEDY = Regex("^(.+) \\((.+)\\)$")
private val LABEL_WITH_TEAM_LAZY = Regex("^(.+?) \\((.+)\\)$")

/** Подпись команды без крайних и повторных пробелов; пустая строка → null. */
internal fun normalizeCommandName(raw: String?): String? =
    raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

/** true, если подписи совпадают с точностью до регистра и пробелов (обе пустые — тоже совпадение). */
internal fun sameCommandName(a: String?, b: String?): Boolean =
    normalizeCommandName(a).equals(normalizeCommandName(b), ignoreCase = true)

/**
 * Подпись клубной команды для протокола: «Клуб (Команда)». Если команды нет или она называется
 * так же, как клуб, — только название клуба.
 */
internal fun teamLabel(clubName: String, teamName: String?): String {
    val club = normalizeCommandName(clubName) ?: clubName
    val team = normalizeCommandName(teamName) ?: return club
    return if (team.equals(club, ignoreCase = true)) club else "$club ($team)"
}

/**
 * Уникальные подписи команд из протокола соревнования без учёта регистра и пробелов: самые частые
 * первыми, для каждой — самое частое написание.
 */
internal fun distinctProtocolNames(names: List<String?>, limit: Int): List<String> =
    names.mapNotNull(::normalizeCommandName)
        .groupBy { it.lowercase() }
        .values
        .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first().lowercase() })
        .take(limit)
        .map { spellings -> spellings.groupingBy { it }.eachCount().maxBy { it.value }.key }

/**
 * Названия клуба (в нижнем регистре), которые мог иметь в виду пользователь: подпись целиком
 * и её часть до « (Команда)».
 */
internal fun clubNameCandidates(commandName: String?): List<String> {
    val normalized = normalizeCommandName(commandName) ?: return emptyList()
    val clubParts = listOf(LABEL_WITH_TEAM_GREEDY, LABEL_WITH_TEAM_LAZY)
        .mapNotNull { it.find(normalized)?.groupValues?.get(1) }
    return (listOf(normalized) + clubParts).map { it.lowercase() }.distinct()
}

/** Вариант подписи команды при регистрации: клубная команда пользователя либо клуб без подходящей команды. */
internal data class TeamOption(
    val teamId: String?,
    val clubId: String,
    val clubName: String,
    val teamName: String?,
) {
    val label: String get() = teamLabel(clubName, teamName)
}

/**
 * Подпись, которую подставить в поле команды при открытии регистрации:
 * 1. вариант, с которым пользователь регистрировался в прошлый раз;
 * 2. единственная клубная команда;
 * 3. единственный клуб, если команд нет;
 * 4. прошлая подпись, если пользователь не состоит ни в одном клубе.
 * При нескольких командах без прошлого выбора — null: пусть пользователь выберет сам.
 */
internal fun suggestCommandName(
    options: List<TeamOption>,
    lastTeamId: String?,
    lastCommandName: String?,
): String? {
    options.firstOrNull { (lastTeamId != null && it.teamId == lastTeamId) }
        ?.let { return it.label }
    options.firstOrNull { normalizeCommandName(lastCommandName) != null && sameCommandName(it.label, lastCommandName) }
        ?.let { return it.label }
    val teams = options.filter { it.teamId != null }
    if (teams.size == 1) return teams.single().label
    if (teams.isEmpty() && options.size == 1) return options.single().label
    if (options.isEmpty()) return normalizeCommandName(lastCommandName)
    return null
}
