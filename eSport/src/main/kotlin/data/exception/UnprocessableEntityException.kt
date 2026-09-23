package com.competra.data.exception

/**
 * Запрос корректен по форме, но не может быть выполнен в текущем состоянии предметной области
 * (например, трекинг для соревнования, которое не идёт сегодня, или без согласия участника).
 * Ловится глобально в StatusPages.kt и превращается в HTTP 422.
 */
class UnprocessableEntityException(message: String) : RuntimeException(message)
