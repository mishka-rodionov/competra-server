package com.competra.data.requests.orienteering

/**
 * @property commandName Подпись команды для протокола (свободный текст).
 * @property teamId Клубная команда пользователя, если подпись выбрана из его команд. Сервер проверяет
 * членство; если пользователь в команде не состоит — ссылка отбрасывается, текст сохраняется.
 */
data class RegisterParticipantRequest(
    val competitionId: String,
    val groupId: Long,
    val firstName: String,
    val lastName: String,
    val commandName: String? = null,
    val teamId: String? = null
)
