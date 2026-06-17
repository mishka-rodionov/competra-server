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

## Conflict Detection (Server-Wins)

При upsert сервис сравнивает `req.serverUpdatedAt` (timestamp последней известной клиенту версии) с текущим серверным значением. Если сервер новее → HTTP 409 с телом текущей серверной записи; клиент перезаписывает локальные данные и повторяет запрос.

**Какой `updatedAt` использовать для сравнения:**

Соревнование хранится в двух таблицах: `Competitions` (comp) и `OrienteeringCompetitions` (orient). У каждой своя колонка `updatedAt`. `CompetitionStatusScheduler` каждые 5 минут автоматически обновляет `comp.updatedAt` при смене статуса, **не трогая** `orient.updatedAt`.

- Conflict-check для `OrienteeringCompetitionRequest` сравнивает с **`orient.updatedAt`** — иначе плановые обновления статуса порождали бы ложные 409.
- Android хранит `orient.updatedAt` в отдельном поле `OrienteeringCompetition.serverUpdatedAt` (`orient_server_updated_at` в Room), отдельно от `Competition.serverUpdatedAt` (`comp.updatedAt`). Оба поля нужны: они ссылаются на разные таблицы с разной семантикой.

## Межпроектные связи

Этот проект — центральный сервер экосистемы из четырёх репозиториев:

| Проект | Путь | Роль |
|---|---|---|
| **competra-android** | `/Users/rodionov/android_projects/competra-android` | Android-клиент (Retrofit + Room + NFC) |
| **competra-web** | `/Users/rodionov/web_projects/competra-web` | Веб-клиент (Kotlin/Wasm + Ktor client) |
| **mapper** | `/Users/rodionov/android_projects/mapper` | Qt/C++ редактор карт, поставщик IOF XML |

### Правила для Claude

**При добавлении или изменении эндпоинта** — **спроси пользователя**: нужно ли обновить клиентский код в `competra-android` и/или `competra-web`? Оба клиента зависят от одного API-контракта.

**При изменении формата ответа** (`CommonModel<T>`, структуры DTO, кодов ошибок) — **спроси**: не сломается ли парсинг на Android (`data:remote`) или в Web (`shared/data/api`)?

**При изменении эндпоинтов авторизации** (`/user/login`, `/user/verify_code`, `/refresh_token`) — **спроси**: нужно ли обновить `AuthRepository` в обоих клиентах?

**При изменении `IOFXmlParser.kt`** или эндпоинта `POST /event/orienteering/import/courses` — **спроси**: не сломается ли импорт дистанций из Mapper? Mapper экспортирует IOF XML, Android (`DistanceRepository.importFromXml`) и Web загружают его на этот эндпоинт.

### Контракт, общий для всех клиентов
- Все ответы: `{"status": 1, "result": ..., "errors": [...]}` — `status == 1` означает успех
- Timestamps: Unix milliseconds (Long)
- IDs соревнований: UUID (`VARCHAR(200)`); IDs участников в публичных эндпоинтах: Long (`remoteId`)
