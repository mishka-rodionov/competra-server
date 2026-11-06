package com.competra.data.services

/** Регистр, ё/е и лишние пробелы не различаем — организаторы вводят имена как придётся. Чистая функция. */
internal fun normalizePersonName(raw: String): String =
    raw.trim().lowercase().replace('ё', 'е').replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

/**
 * Совпадает ли участник протокола с профилем пользователя. Сравниваем полное имя целиком, поэтому
 * засчитываются и перепутанные местами фамилия/имя, и «Фамилия Имя» одной строкой в одном поле,
 * и добавленное отчество. Чистая функция — покрыта unit-тестом.
 */
internal fun namesMatch(
    userFirstName: String,
    userLastName: String,
    userMiddleName: String?,
    participantFirstName: String,
    participantLastName: String,
): Boolean {
    val first = normalizePersonName(userFirstName)
    val last = normalizePersonName(userLastName)
    if (first.isEmpty() || last.isEmpty()) return false
    val middle = userMiddleName?.let(::normalizePersonName).orEmpty()

    val participant = normalizePersonName("$participantLastName $participantFirstName")
    val variants = buildList {
        add("$last $first")
        add("$first $last")
        if (middle.isNotEmpty()) {
            add("$last $first $middle")
            add("$first $middle $last")
        }
    }
    return participant in variants
}

/**
 * Можно ли перенести новое ФИО из профиля в регистрацию: строка участника всё ещё совпадает со старым
 * ФИО профиля (с точностью до [normalizePersonName]). Если не совпадает — ФИО правил организатор
 * или пользователь записался под другим именем, такую строку не трогаем. Чистая функция.
 */
internal fun isRegistrationNameFromProfile(
    oldFirstName: String,
    oldLastName: String,
    participantFirstName: String,
    participantLastName: String,
): Boolean {
    val first = normalizePersonName(oldFirstName)
    val last = normalizePersonName(oldLastName)
    if (first.isEmpty() || last.isEmpty()) return false
    return normalizePersonName(participantFirstName) == first && normalizePersonName(participantLastName) == last
}
