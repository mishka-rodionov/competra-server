package com.competra

import com.competra.data.database.configureDatabases
import com.competra.data.scheduler.configureStatusScheduler
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
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
}
