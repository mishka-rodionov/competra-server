package com.competra.domain.orienteering

/**
 * Места по уже отсортированным ключам результатов (лучший — первым) по правилам соревнований:
 * равные ключи делят место, следующий за ними получает место по числу участников выше него
 * (1, 1, 3, а не 1, 1, 2).
 *
 * Порт Android-клиента: `competitionRanks` в `:domain` (`models/orienteering/CompetitionRanks.kt`) —
 * меняешь здесь, поменяй и там.
 */
fun <K> competitionRanks(sortedKeys: List<K>): List<Int> {
    var rank = 0
    return sortedKeys.mapIndexed { index, key ->
        if (index == 0 || key != sortedKeys[index - 1]) rank = index + 1
        rank
    }
}
