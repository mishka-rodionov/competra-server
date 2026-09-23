package com.competra

/**
 * Режим процесса. Задаётся переменной окружения `APP_MODE`; по умолчанию — [MAIN].
 *
 * Оба режима собираются в один образ, но ставят разные наборы плагинов и роутов: [TRACKING] не
 * запускает шедулеры и не монтирует основной API (иначе дубли FCM-уведомлений и гонки статусов),
 * а [MAIN] не монтирует роуты трекинга.
 */
enum class AppMode {
    /** Основное приложение: соревнования, участники, результаты, профиль. */
    MAIN,

    /** Процесс онлайн-трекинга участников (`/api/live-track/`). */
    TRACKING;

    companion object {
        /** Читает режим из `APP_MODE`; неизвестное значение — ошибка старта, а не тихий [MAIN]. */
        fun fromEnv(): AppMode = when (val raw = System.getenv("APP_MODE")?.trim()?.lowercase()) {
            null, "", "main" -> MAIN
            "tracking" -> TRACKING
            else -> error("Unknown APP_MODE=$raw (expected: main | tracking)")
        }
    }
}
