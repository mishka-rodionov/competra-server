package com.competra.data.exception

/**
 * Сервис временно не готов обслуживать запрос (например, процесс трекинга ещё не поднял схему
 * своей БД). Клиенту стоит повторить позже. Ловится глобально в StatusPages.kt → HTTP 503.
 */
class ServiceUnavailableException(message: String) : RuntimeException(message)
