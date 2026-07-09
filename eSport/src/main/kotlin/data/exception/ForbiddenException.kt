package com.competra.data.exception

/**
 * Бросается сервисами клубов/команд, когда вызывающий userId аутентифицирован,
 * но не имеет нужной роли (FOUNDER/ADMIN) или не состоит в клубе/команде.
 * Ловится глобально в StatusPages.kt и превращается в HTTP 403.
 */
class ForbiddenException(message: String) : RuntimeException(message)
