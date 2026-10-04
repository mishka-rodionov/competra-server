# API — список эндпоинтов

Полный перечень HTTP/WebSocket-эндпоинтов сервера. **Обновляется при каждом добавлении, изменении
или удалении эндпоинта** (см. правило в `CLAUDE.md`).

## Общее

- Базовый префикс API — `/api` (прод: `https://api.competra.ru/api/...`).
- Все ответы обёрнуты в `CommonModel<T>`: `{"status": 1|0, "result": ..., "errors": [{code, message}]}`.
- 🔒 — требуется JWT (`Authorization: Bearer <accessToken>`, блок `authenticate("auth-jwt")`),
  `userId` берётся из claim токена. Без пометки — публичный эндпоинт.
- Конфликт версий при upsert (server-wins) → HTTP 409 с текущей серверной записью в `result`
  (обрабатывается глобально в `StatusPages.kt`).
- Rate-limit задаётся в `nginx/conf.d/competra.conf` (auth — 5/мин, `save/*` и live-экраны — свои зоны,
  остальное `/api/` — 60/мин).

## Служебные (вне `/api`)

| Метод | Путь | Описание | Где |
|---|---|---|---|
| GET | `/health` | Liveness: процесс жив | `HealthCheck.kt` |
| GET | `/health/ready` | Readiness: БД отвечает (503, если нет) | `HealthCheck.kt` |
| GET | `/openapi` | OpenAPI / Swagger UI | `HTTP.kt` |
| WS | `/ws` | Тестовый echo-сокет (в проде nginx не проксирует) | `Sockets.kt` |
| GET | `/rabbitmq` | Тестовая публикация в `test-exchange` (в проде nginx не проксирует) | `Frameworks.kt` |

## Пользователь и авторизация — `data/database/Databases.kt`

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| POST | `/api/user/login` | `EmailRequest` | Отправить код подтверждения на email |
| POST | `/api/user/register` | `UserRequest` | Регистрация, отправка кода на email |
| POST | `/api/user/verify_code` | `CodeVerificationRequest` | Проверка кода → access + refresh токены |
| POST | `/api/refresh_token` | `RefreshRequest` | Обновление access-токена по refresh-токену |
| GET 🔒 | `/api/user/profile` | — | Профиль текущего пользователя |
| PATCH 🔒 | `/api/user/profile` | `UserProfileRequest` | Обновить профиль |
| DELETE 🔒 | `/api/user/me` | — | Удалить аккаунт (участники обезличиваются, результаты сохраняются) |

## Загрузка файлов — `Routing.kt`

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| POST 🔒 | `/api/upload/file` | multipart: файл + `type` (по умолчанию `avatar`) | Загрузка файла → `UploadResponse(url)` |

## Ориентирование — `data/routing/OrienteeringRouting.kt`

### Публичные

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/event/orienteering/competitions/public` | query: `kind_of_sports[]`, `statuses[]`, `date_from`, `date_to`, `includeTest`, `query`, `page`, `limit` | Публичный список соревнований с фильтрами и пагинацией |
| GET | `/api/event/orienteering/competitions/public/{id}` | query: `userId` (опц.) | Детали соревнования (`CompetitionDetailResponse`); `id` — UUID или legacy-число |
| GET | `/api/event/orienteering/competitions/{id}` | — | Orienteering-часть соревнования по UUID |
| GET | `/api/event/orienteering/participants` | query: `groupId` | Участники группы |
| GET | `/api/event/orienteering/participants/competition` | query: `competitionId` | Участники соревнования |
| GET | `/api/event/orienteering/participantGroups` | query: `competitionId` | Группы соревнования |
| GET | `/api/event/orienteering/results/competition` | query: `competitionId` | Результаты соревнования |
| GET | `/api/event/orienteering/organizers` | query: `competitionId` | Организаторы соревнования (с ролями) |
| GET | `/api/event/orienteering/distances` | query: `competitionId` | Дистанции соревнования (КП, очки, координаты) |

### Под JWT 🔒

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| POST | `/api/event/orienteering/save/competitions` | `OrienteeringCompetitionRequest` | Upsert соревнования (conflict-check по `orient.updatedAt` → 409) |
| GET | `/api/event/orienteering/competitions` | — | Соревнования, созданные пользователем |
| GET | `/api/event/orienteering/competitions/registered` | — | Соревнования, на которые пользователь зарегистрирован |
| DELETE | `/api/event/orienteering/competitions/{id}` | — | Удалить соревнование |
| POST | `/api/event/orienteering/save/participantGroup` | `List<ParticipantGroupRequest>` | Upsert групп |
| DELETE | `/api/event/orienteering/participantGroups/{id}` | — | Удалить группу |
| POST | `/api/event/orienteering/save/organizers` | `List<OrganizerRequest>` | Upsert организаторов |
| DELETE | `/api/event/orienteering/organizers/{id}` | — | Удалить организатора |
| POST | `/api/event/orienteering/save/participant` | `OrienteeringParticipantRequest` | Upsert одного участника |
| POST | `/api/event/orienteering/save/participants` | `List<OrienteeringParticipantRequest>` | Upsert участников пачкой |
| DELETE | `/api/event/orienteering/participants/{id}` | — | Удалить участника |
| POST | `/api/event/orienteering/save/result` | `OrienteeringResultRequest` | Upsert одного результата |
| POST | `/api/event/orienteering/save/results` | `List<OrienteeringResultRequest>` | Upsert результатов пачкой |
| DELETE | `/api/event/orienteering/results/{id}` | — | Удалить результат |
| POST | `/api/event/orienteering/save/distances` | `List<DistanceRequest>` | Upsert дистанций |
| DELETE | `/api/event/orienteering/distances/{id}` | — | Удалить дистанцию |
| POST | `/api/event/orienteering/import/courses` | multipart: IOF XML-файл + `competitionId` | Импорт дистанций из IOF XML (Mapper) через `IOFXmlParser` |
| POST | `/api/event/orienteering/register` | `RegisterParticipantRequest` | Самостоятельная регистрация пользователя на соревнование (`commandName` ≤ 200 символов, необязательный `teamId` своей клубной команды) |
| DELETE | `/api/event/orienteering/register/{competitionId}` | — | Отмена своей регистрации |

Права на save/delete проверяются по ролям организаторов (MAIN/JUDGE/SECRETARY/COURSE_SETTER/OTHER).

### Команда при регистрации — `data/routing/RegistrationTeamRouting.kt` 🔒

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/event/orienteering/competitions/{id}/registration-team-options` | — | Мои клубные команды по виду спорта соревнования (подпись «Клуб (Команда)»), подписи команд из протокола и `suggestedCommandName` для автоподстановки |
| GET | `/api/clubs/match` | query: `name` | Клубы, чьё название совпадает с введённой подписью команды (без учёта регистра), где пользователь не состоит — для подсказки «вступить в клуб» |

У участника хранится текст `commandName` (подпись в протоколе) и nullable `team_id` (ссылка на `teams`, `ON DELETE SET NULL`). `team_id` ставится только при самостоятельной регистрации; при сохранении участника организатором ссылка сохраняется, пока не изменилась подпись (без учёта регистра и пробелов).

## Привязка участников к аккаунтам — `data/routing/ParticipantLinkRouting.kt` 🔒

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/event/orienteering/link-requests/suggestions` | — | Непривязанные участники, похожие на текущего пользователя |
| POST | `/api/event/orienteering/link-requests` | `CreateParticipantLinkRequest` | Создать заявку на привязку (пуш организаторам) |
| GET | `/api/event/orienteering/link-requests/mine` | — | Мои заявки |
| GET | `/api/event/orienteering/link-requests/competition` | query: `competitionId` | Заявки по соревнованию (для организатора) |
| GET | `/api/event/orienteering/link-requests/pending-counts` | — | `competitionId → число PENDING-заявок` по соревнованиям, где пользователь управляет участниками |
| PUT | `/api/event/orienteering/link-requests/{id}` | `ReviewParticipantLinkRequest` | Одобрить/отклонить заявку |
| DELETE | `/api/event/orienteering/link-requests/{id}` | — | Отозвать свою заявку |
| POST | `/api/event/orienteering/participants/{id}/unlink` | — | Отвязать участника от аккаунта |

## Клубы — `data/routing/ClubsRouting.kt`

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/clubs` | query: `query`, `page`, `limit` | Поиск клубов |
| GET | `/api/clubs/{id}` | — | Клуб по id |
| GET | `/api/clubs/{id}/members` | — | Члены клуба |
| POST 🔒 | `/api/clubs` | `CreateClubRequest` | Создать клуб |
| GET 🔒 | `/api/clubs/mine` | — | Мои клубы |
| PUT 🔒 | `/api/clubs/{id}` | `UpdateClubRequest` | Обновить клуб |
| DELETE 🔒 | `/api/clubs/{id}` | — | Удалить клуб |
| DELETE 🔒 | `/api/clubs/{id}/members/{userId}` | — | Исключить члена клуба / выйти |
| PUT 🔒 | `/api/clubs/{id}/members/{userId}/role` | `ChangeRoleRequest` | Сменить роль члена клуба |
| POST 🔒 | `/api/clubs/{id}/join-requests` | — | Подать заявку на вступление |
| GET 🔒 | `/api/clubs/{id}/join-requests` | — | Заявки на вступление в клуб |
| GET 🔒 | `/api/clubs/join-requests/mine` | — | Мои заявки на вступление |
| PUT 🔒 | `/api/clubs/{id}/join-requests/{requestId}` | `ReviewJoinRequestRequest` | Рассмотреть заявку |

## Команды — `data/routing/TeamsRouting.kt`

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/clubs/{clubId}/teams` | — | Команды клуба |
| GET | `/api/teams/{id}` | — | Команда по id |
| GET | `/api/teams/{id}/members` | — | Состав команды |
| POST 🔒 | `/api/clubs/{clubId}/teams` | `CreateTeamRequest` | Создать команду |
| PUT 🔒 | `/api/teams/{id}` | `UpdateTeamRequest` | Обновить команду |
| DELETE 🔒 | `/api/teams/{id}` | — | Удалить команду |
| POST 🔒 | `/api/teams/{id}/members` | `AddTeamMemberRequest` | Добавить члена клуба в команду |
| DELETE 🔒 | `/api/teams/{id}/members/{clubMemberId}` | — | Убрать из команды |
| PUT 🔒 | `/api/teams/{id}/members/{clubMemberId}/role` | `ChangeTeamMemberRoleRequest` | Сменить роль в команде |

## Рейтинги — `data/routing/RatingRouting.kt`

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| GET | `/api/ratings` | query: `query`, `page`, `limit` | Поиск рейтингов |
| GET | `/api/clubs/{clubId}/ratings` | — | Рейтинги клуба |
| GET | `/api/ratings/{id}` | — | Рейтинг по id |
| GET | `/api/ratings/{id}/competitions` | — | Соревнования, входящие в рейтинг |
| GET | `/api/ratings/{id}/competitions/{competitionId}/mapping-suggestions` | — | Подсказки маппинга групп соревнования на группы рейтинга |
| GET | `/api/ratings/{id}/standings` | query: `groupId` | Таблица рейтинга по группе |
| POST 🔒 | `/api/clubs/{clubId}/ratings` | `CreateRatingRequest` | Создать рейтинг |
| PUT 🔒 | `/api/ratings/{id}` | `UpdateRatingRequest` | Обновить рейтинг |
| DELETE 🔒 | `/api/ratings/{id}` | — | Удалить рейтинг |
| POST 🔒 | `/api/ratings/{id}/competitions` | `AddCompetitionToRatingRequest` | Добавить соревнование в рейтинг |
| DELETE 🔒 | `/api/ratings/{id}/competitions/{competitionId}` | — | Убрать соревнование из рейтинга |
| PUT 🔒 | `/api/ratings/{id}/competitions/{competitionId}/mapping` | `SetGroupMappingRequest` | Сохранить маппинг групп |

## Тренировочный дневник — `data/routing/DiaryRouting.kt` 🔒

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| POST | `/api/diary/workouts` | `List<WorkoutRequest>` | Upsert тренировок (без server-wins/409) |
| GET | `/api/diary/workouts` | — | Тренировки пользователя |
| DELETE | `/api/diary/workouts/{id}` | — | Удалить тренировку |

## Устройства и пуши — `data/routing/DeviceRouting.kt` 🔒

| Метод | Путь | Тело / параметры | Описание |
|---|---|---|---|
| POST | `/api/devices/fcm-token` | `FcmTokenRequest` | Зарегистрировать FCM-токен устройства |
| DELETE | `/api/devices/fcm-token` | query `token` или `FcmTokenRequest` | Удалить FCM-токен |
| POST | `/api/devices/fcm-token/test-push` | query: `title`, `body` | Дебаг: отправить пуш самому себе |

## Онлайн-трекинг — отдельный процесс (`APP_MODE=tracking`), `tracking/`

Nginx проксирует `/api/live-track/` в контейнер `tracking`.

| Метод | Путь | Тело / параметры | Описание | Где |
|---|---|---|---|---|
| GET | `/health` | — | Liveness процесса трекинга (только внутри сети) | `TrackingModule.kt` |
| GET | `/api/live-track/health` | — | Состояние БД трекинга и основной БД | `TrackingModule.kt` |
| GET | `/api/live-track/competitions/{competitionId}/distances` | — | Дистанции соревнования, по которым есть треки | `LiveTrackRouting.kt` |
| GET | `/api/live-track/distances/{distanceId}/live` | query: `since` (курсор) | Живой снимок дистанции | `LiveTrackRouting.kt` |
| GET | `/api/live-track/distances/{distanceId}/tracks` | — | Треки дистанции | `LiveTrackRouting.kt` |
| POST 🔒 | `/api/live-track/sessions` | `StartSessionRequest` | Старт/возобновление сессии трекинга | `LiveTrackRouting.kt` |
| POST 🔒 | `/api/live-track/sessions/{sessionId}/points` | `PointsBatchRequest` | Батч GPS-точек | `LiveTrackRouting.kt` |
| POST 🔒 | `/api/live-track/sessions/{sessionId}/stop` | — | Ручная остановка сессии | `LiveTrackRouting.kt` |
