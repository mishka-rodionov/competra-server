package com.sportenth

import com.sportenth.data.database.configureDatabases
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
    configureRouting()
}
