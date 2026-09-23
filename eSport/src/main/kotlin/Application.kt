package com.competra

import com.competra.data.database.configureDatabases
import com.competra.data.scheduler.configureReminderNotificationScheduler
import com.competra.data.scheduler.configureStatusScheduler
import com.competra.tracking.trackingModule
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

/**
 * Точка входа Ktor (см. application.yaml). Один и тот же образ запускается в двух режимах
 * ([AppMode], переменная `APP_MODE`): основное приложение и отдельный процесс онлайн-трекинга —
 * чтобы сбой трекинга не мог уронить приём финиша и результатов (спека: competra-android, docs/specs/live-tracking.md).
 */
fun Application.module() {
    when (AppMode.fromEnv()) {
        AppMode.MAIN -> mainModule()
        AppMode.TRACKING -> trackingModule()
    }
}

/** Основное приложение: весь API, кроме онлайн-трекинга, и фоновые шедулеры. */
private fun Application.mainModule() {
    configureHTTP()
    configureSecurity()
    configureAuthentication()
    configureMonitoring()
    configureSerialization()
    configureDatabases()
    configureFrameworks()
    configureSockets()
    configureStatusPages()
    configureRouting()
    configureStatusScheduler()
    configureReminderNotificationScheduler(attributes[FcmServiceKey], attributes[CompetitionNotificationLogServiceKey])
}
