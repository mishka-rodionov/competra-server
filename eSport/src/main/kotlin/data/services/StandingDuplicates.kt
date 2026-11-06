package com.competra.data.services

/** Строка таблицы рейтинга глазами поиска дублей. */
internal data class StandingIdentity(
    val key: String,
    val userId: String?,
    val firstName: String,
    val lastName: String,
)

/**
 * Совместимы ли имена одного человека: равны (без учёта регистра, ё/е), одно пустое, или одно —
 * сокращение другого: «М.», «М», «Е.А.», «Алекс.» ~ «Алексей».
 */
internal fun firstNamesCompatible(a: String, b: String): Boolean {
    val x = normalizePersonName(a)
    val y = normalizePersonName(b)
    if (x == y || x.isEmpty() || y.isEmpty()) return true
    return isAbbreviationOf(x, y) || isAbbreviationOf(y, x)
}

private fun isAbbreviationOf(short: String, full: String): Boolean {
    if (!short.endsWith('.') && short.length > 1) return false
    val stem = short.substringBefore('.').trim()
    return stem.isNotEmpty() && full.startsWith(stem)
}

/**
 * Группы ключей, похожих на одного человека: одинаковая фамилия и попарно совместимые имена
 * (транзитивно). Группы только из аккаунтов не возвращаем — два аккаунта объединить нельзя.
 * Чистая функция — покрыта unit-тестом.
 */
internal fun findDuplicateGroups(entries: List<StandingIdentity>): List<List<String>> {
    val parent = IntArray(entries.size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) x = parent[x]
        return x
    }
    val lastNames = entries.map { normalizePersonName(it.lastName) }
    for (i in entries.indices) {
        if (lastNames[i].isEmpty()) continue
        for (j in i + 1 until entries.size) {
            if (lastNames[i] != lastNames[j]) continue
            if (entries[i].userId != null && entries[j].userId != null) continue
            if (firstNamesCompatible(entries[i].firstName, entries[j].firstName)) parent[find(j)] = find(i)
        }
    }
    return entries.indices
        .groupBy(::find)
        .values
        .filter { group -> group.size > 1 && group.any { entries[it].userId == null } }
        .map { group -> group.map { entries[it].key } }
}
