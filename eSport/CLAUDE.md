# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # Build project
./gradlew buildFatJar    # Build executable JAR
./gradlew run            # Run locally on :8080
./gradlew test           # Run tests
./gradlew test --tests "com.example.FooTest"  # Run single test

docker-compose up        # Full stack: app + PostgreSQL + RabbitMQ
```

## Architecture

**Stack**: Kotlin + Ktor 3.2.3, Exposed ORM, Koin DI, PostgreSQL, RabbitMQ

**Layers**:
- `data/routing/` — HTTP route handlers (split into public routes and JWT-protected routes)
- `data/services/` — Business logic; `smtp/` subpackage handles email verification and JWT generation
- `data/database/entity/` — Exposed table definitions (schema created automatically on startup via `SchemaUtils.create()`)
- `data/requests/` — Input DTOs
- `data/response/` — Output DTOs, all wrapped in `CommonModel<T>` (`status: 1/0`, `result`, `errors`)
- `domain/` — Enums: `KindOfSport`, `SportsCategory`, `Gender`, `Qualification`

**Auth flow**: Register (email) → email verification code via Yandex SMTP → verify code → JWT issued. Access token TTL 15m, refresh token TTL 30d (stored in `RefreshTokens` table). JWT claim: `userId`.

**Route structure**: Public endpoints at `/event/orienteering/competitions/public/...` and `/event/orienteering/participants`. Protected endpoints under JWT auth block for save/register/delete operations. Auth endpoints at `/user/register`, `/user/login`, `/user/verify_code`, `/refresh_token`.

## Environment Variables

Copy `.env.example` to `.env`. Required:
```
DB_PASSWORD, DB_USER, DB_URL
JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE
RABBITMQ_PASSWORD
SMTP_USER, SMTP_PASSWORD  # Yandex Mail
```

## Key Conventions

- **IDs**: UUIDs stored as `VARCHAR(200)`
- **Timestamps**: Unix milliseconds (Long) for dates
- **Upsert pattern**: Services use insert-or-update (idempotent)
- **DB transactions**: Suspended with `Dispatchers.IO`
- **RabbitMQ**: `test-queue → test-exchange` (direct), `dlq → dlx` (dead-letter)
- **OpenAPI**: Served at `/openapi`
