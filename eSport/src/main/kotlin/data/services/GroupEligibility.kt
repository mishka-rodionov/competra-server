package com.competra.data.services

import com.competra.domain.user.Gender
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Ограничение группы по полу. ParticipantGroups.gender пишется клиентами в разных форматах:
 * веб — "M"/"F", Android — "MALE"/"FEMALE"/"MIXED" (Gender.name). Null, пустая строка,
 * "MIXED" и прочие значения — без ограничения.
 * Чистая функция — покрыта unit-тестом.
 */
internal fun groupGenderRestriction(gender: String?): Gender? = when (gender?.trim()?.uppercase()) {
    "M", "MALE" -> Gender.MALE
    "F", "FEMALE" -> Gender.FEMALE
    else -> null
}

/**
 * Год рождения из users.birth_date. Колонка строковая: клиенты шлют Unix-миллисекунды числом,
 * Gson кладёт их в String как есть; у пользователей из ранних версий бывает пустая строка.
 *
 * Клиенты кодируют дату как полночь — веб и Android-DatePicker в UTC, но старые клиенты могли
 * слать и локальную полночь (например, МСК = 21:00 UTC предыдущего дня). Сдвиг на +12 часов
 * перед взятием даты в UTC даёт правильный день для любого смещения меньше ±12 ч.
 * Чистая функция — покрыта unit-тестом.
 */
internal fun parseBirthYear(raw: String?): Int? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val millis = value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
    if (millis != null) {
        return Instant.ofEpochMilli(millis).plusSeconds(12 * 3600).atZone(ZoneOffset.UTC).year
    }
    return try {
        LocalDate.parse(value.take(10)).year
    } catch (e: DateTimeException) {
        null
    }
}

/** Год соревнования в его часовом поясе; неизвестный пояс — UTC. */
internal fun competitionYear(startDateMillis: Long, timeZoneId: String?): Int {
    val zone = try {
        ZoneId.of(timeZoneId ?: "UTC")
    } catch (e: DateTimeException) {
        ZoneOffset.UTC
    }
    return Instant.ofEpochMilli(startDateMillis).atZone(zone).year
}

/**
 * Проверяет, может ли пользователь зарегистрироваться в группу по полу и возрасту.
 * Возраст считается по году рождения, как принято в спортивном ориентировании:
 * возраст = год соревнования − год рождения (М14 — все, кому в этом году исполняется 14).
 * minAge/maxAge ≤ 0 трактуются как «без ограничения».
 *
 * Если у группы есть ограничение, а в профиле нет пола/даты рождения — регистрация запрещена
 * с просьбой заполнить профиль.
 *
 * @return null — можно регистрироваться, иначе текст ошибки для пользователя.
 * Чистая функция — покрыта unit-тестом.
 */
internal fun checkGroupEligibility(
    groupTitle: String,
    groupGender: String?,
    minAge: Int?,
    maxAge: Int?,
    userGender: Gender?,
    userBirthDate: String?,
    competitionYear: Int,
): String? {
    val requiredGender = groupGenderRestriction(groupGender)
    if (requiredGender != null) {
        if (userGender == null) return "Укажите пол в профиле, чтобы зарегистрироваться в группу $groupTitle"
        if (userGender != requiredGender) {
            val who = if (requiredGender == Gender.MALE) "мужчин" else "женщин"
            return "Группа $groupTitle — только для $who"
        }
    }

    val min = minAge?.takeIf { it > 0 }
    val max = maxAge?.takeIf { it > 0 }
    if (min == null && max == null) return null

    val birthYear = parseBirthYear(userBirthDate)
        ?: return "Укажите дату рождения в профиле, чтобы зарегистрироваться в группу $groupTitle"
    val age = competitionYear - birthYear
    if ((min != null && age < min) || (max != null && age > max)) {
        // Диапазон переводим в годы рождения — участнику так привычнее, чем возраст.
        val oldestYear = max?.let { competitionYear - it }
        val youngestYear = min?.let { competitionYear - it }
        val range = when {
            oldestYear != null && youngestYear != null ->
                if (oldestYear == youngestYear) "$oldestYear г.р." else "$oldestYear–$youngestYear г.р."
            youngestYear != null -> "$youngestYear г.р. и старше"
            else -> "$oldestYear г.р. и моложе"
        }
        return "Группа $groupTitle — для участников $range, ваш год рождения — $birthYear"
    }
    return null
}
