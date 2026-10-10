val exposed_version = project.property("exposed_version") as String
val h2_version = project.property("h2_version") as String
val koin_version = project.property("koin_version") as String
val kotlin_version = project.property("kotlin_version") as String
val logback_version = project.property("logback_version") as String

plugins {
    kotlin("jvm") version "2.4.21"
    id("io.ktor.plugin") version "3.6.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

group = "com.competra"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(17) // или 11, если нужен Java 11
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-auto-head-response")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-double-receive")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-gson")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("io.github.damirdenis-tudor:ktor-server-rabbitmq:1.5.0")
    testImplementation("io.ktor:ktor-server-test-host")

    implementation("com.h2database:h2:$h2_version")
    implementation("io.insert-koin:koin-ktor:$koin_version")
    implementation("io.insert-koin:koin-logger-slf4j:$koin_version")

    implementation("ch.qos.logback:logback-classic:${logback_version}")
    implementation("com.github.loki4j:loki-logback-appender:2.1.0")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    // Exposed + PostgreSQL
    implementation("org.jetbrains.exposed:exposed-dao:${exposed_version}")
    implementation("org.jetbrains.exposed:exposed-core:${exposed_version}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${exposed_version}")
    implementation("org.jetbrains.exposed:exposed-java-time:${exposed_version}")
    implementation("org.postgresql:postgresql:42.7.14")
    // Пул соединений к Postgres: без него каждая транзакция Exposed открывала новое соединение,
    // и всплеск нагрузки мог исчерпать max_connections Postgres (см. docs live-tracking, этап 0).
    implementation("com.zaxxer:HikariCP:7.1.0")

    // SMTP
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // AWS SDK v2 — S3-клиент для Yandex Object Storage
    implementation("software.amazon.awssdk:s3:2.55.13")

    // Firebase Cloud Messaging — HTTP v1 API через Ktor client + google-auth для OAuth2
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.54.0")
}
