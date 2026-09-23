package com.competra.tracking

import com.competra.data.util.requireEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig

/** Пул к собственной БД трекинга по умолчанию; роль в Postgres ограничена `CONNECTION LIMIT 10`. */
private const val DEFAULT_TRACKING_POOL_SIZE = 8

/** Пул к основной БД только на чтение; роль ограничена `CONNECTION LIMIT 3`. */
private const val MAIN_READ_ONLY_POOL_SIZE = 2

/** Сколько ждать соединения из пула, прежде чем отказать запросу. */
private const val CONNECTION_TIMEOUT_MS = 3_000L

/** Потолок на один запрос к основной БД — трекинг не должен держать её долгими запросами. */
private const val MAIN_STATEMENT_TIMEOUT_MS = 3_000

/**
 * Подключения процесса трекинга.
 *
 * @property tracking Собственная БД трекинга (`competra_tracking`): сессии и точки треков.
 * @property mainReadOnly Основная БД (`competra`) только на чтение — один раз на сессию трекинга,
 *   чтобы проверить участника и денормализовать его имя/группу/дистанцию.
 * @property trackingDataSource Пул [tracking] — для health-check напрямую через JDBC.
 * @property mainReadOnlyDataSource Пул [mainReadOnly] — для health-check напрямую через JDBC.
 */
class TrackingDatabases(
    val tracking: Database,
    val mainReadOnly: Database,
    val trackingDataSource: HikariDataSource,
    val mainReadOnlyDataSource: HikariDataSource
)

/**
 * Открывает оба пула. `initializationFailTimeout = -1`: процесс стартует, даже если БД сейчас
 * недоступна, и поднимает соединения, когда она вернётся, — `/api/live-track/health` в это время
 * честно показывает DOWN. Иначе недоступность БД превращалась бы в цикл рестартов контейнера.
 */
fun connectTrackingDatabases(): TrackingDatabases {
    val tracking = HikariDataSource(
        HikariConfig().apply {
            poolName = "competra-tracking"
            jdbcUrl = requireEnv("TRACKING_DB_URL")
            driverClassName = "org.postgresql.Driver"
            username = requireEnv("TRACKING_DB_USER")
            password = requireEnv("TRACKING_DB_PASSWORD")
            maximumPoolSize = System.getenv("TRACKING_DB_POOL_SIZE")?.toIntOrNull() ?: DEFAULT_TRACKING_POOL_SIZE
            minimumIdle = 1
            connectionTimeout = CONNECTION_TIMEOUT_MS
            initializationFailTimeout = -1
            isAutoCommit = false
        }
    )
    val mainReadOnly = HikariDataSource(
        HikariConfig().apply {
            poolName = "competra-main-readonly"
            jdbcUrl = requireEnv("MAIN_RO_DB_URL")
            driverClassName = "org.postgresql.Driver"
            username = requireEnv("MAIN_RO_DB_USER")
            password = requireEnv("MAIN_RO_DB_PASSWORD")
            maximumPoolSize = MAIN_READ_ONLY_POOL_SIZE
            minimumIdle = 0
            connectionTimeout = CONNECTION_TIMEOUT_MS
            initializationFailTimeout = -1
            isAutoCommit = false
            isReadOnly = true
            // Дублирует настройку роли в Postgres (scripts/setup_tracking_db.sh) — на случай, если
            // процесс подключили к основной БД под другой ролью.
            connectionInitSql = "SET statement_timeout = $MAIN_STATEMENT_TIMEOUT_MS"
        }
    )
    // Одна попытка транзакции вместо трёх по умолчанию: при недоступной БД запрос должен быстро
    // получить ошибку (бегун досылает батч сам), а не висеть 3 × connectionTimeout.
    val config = DatabaseConfig { defaultMaxAttempts = 1 }
    return TrackingDatabases(
        tracking = Database.connect(tracking, databaseConfig = config),
        mainReadOnly = Database.connect(mainReadOnly, databaseConfig = config),
        trackingDataSource = tracking,
        mainReadOnlyDataSource = mainReadOnly
    )
}
