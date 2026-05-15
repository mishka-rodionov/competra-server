# Grafana / Loki: чтение логов и дашборды

Шпаргалка по тому, как смотреть логи приложения в Grafana Cloud, писать LogQL-запросы, собирать дашборды и настраивать алерты. Под конкретный набор полей, которые шлёт наш Ktor-бэкенд после интеграции StatusPages + CallId + LogstashLayout.

---

## 1. Какие поля доступны

Каждая запись в Loki — это JSON с фиксированными полями. После `| json` в LogQL они становятся доступны как переменные.

### Labels (индексируемые, фильтрация дешёвая)

| Label | Значение |
|---|---|
| `app` | `competra` |
| `level` | `INFO`, `WARN`, `ERROR` |
| `host` | hostname контейнера |

### JSON-поля в теле сообщения

| Поле | Пример | Когда заполнено |
|---|---|---|
| `ts` | `2026-05-14T13:18:31.886Z` | всегда |
| `msg` | `HTTP GET /health → 200 in 15ms` | всегда |
| `logger` | `Application`, `Exposed`, ... | всегда |
| `thread` | `eventLoopGroupProxy-4-2` | всегда |
| `call_id` | `bd546327-57a3-...` | каждый HTTP-запрос (CallId plugin) |
| `http_method` | `GET`, `POST`, `DELETE` | каждый HTTP-запрос |
| `http_path` | `/event/orienteering/save/competitions` | каждый HTTP-запрос |
| `http_status` | `200`, `404`, `500` | каждый HTTP-запрос |
| `duration_ms` | `15` | каждый HTTP-запрос |
| `user_id` | UUID из JWT | если запрос аутентифицирован |
| `remote_host` | IP клиента | каждый HTTP-запрос |
| `user_agent` | строка UA | каждый HTTP-запрос |
| `query` | querystring | если есть |
| `stack_trace` | многострочный stack trace | при exception |

`call_id` уходит клиенту в response-заголовке `X-Request-Id` и в теле 500-ответа как `result.traceId`. По нему связываются все записи одного запроса — главный инструмент дебага.

---

## 2. Где смотреть — Explore

1. Слева — **Explore** (иконка компаса).
2. Сверху datasource — `grafanacloud-sportenth-logs`.
3. Справа переключатель **Builder / Code** — нажми **Code** для ручных запросов.
4. Time range сверху справа — поставь **Last 15 minutes** при отладке (быстрее).

### Полезные кнопки

- **Live** (правый верхний угол) — потоковое отображение, как `tail -f`.
- **Split** — два запроса бок о бок, удобно сравнивать.
- Клик по полю в раскрытой строке — Grafana автоматически добавит фильтр.
- **Inspector → Stats** — сколько байт прочитал LogQL (важно для лимитов Free плана).

---

## 3. LogQL: базовые запросы

### Всё подряд

```logql
{app="competra"}
```

### Только ошибки

```logql
{app="competra", level="ERROR"}
```

### Один запрос по call_id

```logql
{app="competra"} | json | call_id="bd546327-57a3-4110-80d0-c412f7286dc3"
```

### Все 5xx

```logql
{app="competra"} | json | http_status >= 500
```

### Конкретный эндпоинт

```logql
{app="competra"} | json | http_path="/event/orienteering/save/competitions"
```

### Медленные запросы (>500 мс)

```logql
{app="competra"} | json | duration_ms > 500
```

### Что делал пользователь

```logql
{app="competra"} | json | user_id="<uuid>"
```

### Тело ошибочного запроса (от нашего interceptor'а)

```logql
{app="competra"} |= "Error body for"
```

### Свободный поиск

```logql
{app="competra"} |= "ConflictException"
{app="competra"} |~ "(?i)timeout"        # regex case-insensitive
{app="competra"} != "/health"            # исключить
```

### Операторы фильтрации

| Оператор | Значение |
|---|---|
| `\|=` | строка содержит |
| `!=` | строка НЕ содержит |
| `\|~` | соответствует regex |
| `!~` | НЕ соответствует regex |
| `\| json` | распарсить тело как JSON |
| `\| json field="path.to.field"` | извлечь конкретное поле под другим именем |

---

## 4. Создание дашборда

### Общая последовательность

1. Слева → **Dashboards** → **New** → **New dashboard**.
2. **Add visualization** → выбрать datasource `grafanacloud-sportenth-logs`.
3. Откроется редактор: запрос внизу, визуализация справа, превью посередине.
4. Сверху справа — название панели в поле **Title**.
5. **Apply** — вернуться к дашборду.
6. Иконка дискеты сверху справа дашборда → **Save**.

### Готовые панели

#### Панель 1: «Поток ошибок» — тип `Logs`

```logql
{app="competra", level="ERROR"} | json
```
В правой панели визуализации:
- `Wrap lines` = on
- `Show time` = on
- `Show common labels` = off

Клик по строке — раскрывается JSON со всеми полями и `stack_trace`.

#### Панель 2: «Запросов в минуту по статусу» — тип `Time series`

```logql
sum by (http_status) (rate({app="competra"} | json | __error__="" [1m]))
```

- `__error__=""` отфильтровывает строки, которые не распарсились в JSON.
- В Visualization → `Stacking = Normal` → статусы в стопку.
- `Legend = As table` снизу.

#### Панель 3: «Топ путей по ошибкам» — тип `Bar chart`

```logql
topk(10, sum by (http_path) (
  count_over_time({app="competra"} | json | http_status >= 400 [$__range])
))
```

`$__range` — встроенная переменная Grafana, равная диапазону дашборда.

#### Панель 4: «p95 латентность по путям» — тип `Time series`

```logql
quantile_over_time(0.95,
  {app="competra"} | json | unwrap duration_ms [5m]
) by (http_path)
```

`unwrap duration_ms` превращает строковое поле в число для квантилей.

#### Панель 5: «Heartbeat — приложение живо» — тип `Stat`

```logql
sum(count_over_time({app="competra"}[5m]))
```

Настройки:
- `Value options → Calculate → Last *`
- `Standard options → Color scheme → From thresholds`
- Thresholds: `0` — красный, `1` — зелёный

#### Панель 6: «Здоровье БД» — тип `State timeline`

```logql
sum by (http_status) (
  count_over_time({app="competra"} | json | http_path="/health/ready" [5m])
)
```

Видны переходы 200 → 503 → 200, когда БД отваливалась.

#### Панель 7: «Распределение пользователей» — тип `Pie chart`

```logql
topk(10, sum by (user_id) (
  count_over_time({app="competra"} | json | user_id!="" [$__range])
))
```

### Переменные дашборда

Чтобы фильтровать панели в один клик:

1. Дашборд → шестерёнка → **Variables** → **New variable**.
2. Тип `Query`, datasource — Loki.
3. **Name** = `path`, **Label** = `Path`.
4. **Query**:
   ```
   label_values({app="competra"} | json, http_path)
   ```
5. `Include All option` = on.

В запросах панелей пишешь `http_path="$path"` — путь меняется выпадающим списком сверху.

Аналогично можно сделать `user`, `status`, `level`.

---

## 5. Алерты

Слева → **Alerting** → **Alert rules** → **New alert rule**.

### Алерт 1: приложение пропало

```logql
sum(count_over_time({app="competra"}[5m]))
```
Условие: `Last → Is below 1`. Evaluate every 1m for 5m.

### Алерт 2: всплеск ошибок

```logql
sum(count_over_time({app="competra", level="ERROR"}[5m]))
```
Условие: `Is above 5`.

### Алерт 3: упала БД

```logql
sum(count_over_time({app="competra"} | json | http_path="/health/ready", http_status="503" [5m]))
```
Условие: `Is above 0`.

### Notification channels

`Alerting → Contact points` → создать (Email, Telegram через webhook, Slack). Дальше `Notification policies` связывают алерты с каналами.

---

## 6. Типичные сценарии

### Клиент прислал traceId

1. Explore → `{app="competra"} | json | call_id="<traceId>"`.
2. Видишь связку: HTTP-запрос, exception со stack_trace, тело запроса (`Error body for ...`).
3. Воспроизводишь локально по этим данным.

### Медленный эндпоинт

```logql
{app="competra"} | json | http_path="/event/orienteering/competitions" | duration_ms > 1000
```

По `query` и `user_id` находишь конкретные кейсы.

### Инцидент в конкретное время

Установить time range вокруг момента инцидента, `{app="competra"}`. Кликом по строке добавлять фильтры (path, status, user_id) и сужать выборку.

---

## 7. Troubleshooting

### «No data» в большинстве панелей, но Heartbeat работает

Симптом: панели с `| json | ...` пустые, а простой `count_over_time({app="competra"}[5m])` отдаёт цифру.

Причина: тело логов **не парсится как JSON**. То есть Loki получает plain-text сообщения, и `| json` не может распаковать поля.

Диагностика:
- В Explore выполни `{app="competra"}`, открой одну строку. Если видно одну длинную строку с временем, уровнем и текстом — это plain text, `| json` не сработает.
- В логах приложения при старте было предупреждение `No message layout specified in the config. Using PatternLayout with default settings`.

Решение: в `src/main/resources/logback.xml` в блоке `<message>` использовать `net.logstash.logback.layout.LogstashLayout` (а не `LogstashEncoder` — это разные классы; loki4j ожидает Layout). После правки — пересобрать и задеплоить.

### `401 invalid token`

Токен мёртвый / отозванный. Перевыпустить в Grafana Cloud → Security → Access Policies → Token → Create.

### `401 invalid scope requested`

Токен валиден, но у политики нет права писать логи. В Access Policy включить scope `logs:write` (раздел Loki).

### `Connection refused` / `UnknownHostException`

Неверный `LOKI_URL`. Должен быть хост без пути: `https://logs-prod-012.grafana.net`. Без `/loki/api/v1/push` на конце — appender дописывает сам.

### Логи приходят, но Grafana показывает «No logs found»

- Проверь time range — может быть слишком узкий (`Last 5 minutes`, а последний лог был час назад).
- Проверь, что выбрана правильная datasource в Explore.

---

## 8. Лимиты Free плана

- **Logs**: 50 GB ingestion/месяц, retention 14 дней (фиксированно, увеличить нельзя).
- **Metrics**: 10k активных series, 14 дней.
- **Traces**: 50 GB/месяц.
- 3 пользователя в организации.

Реальное потребление: Grafana Cloud Portal → My Account → Usage.

### Если приближаешься к лимиту

В `Monitoring.kt` можно отфильтровать `/health` из CallLogging (UptimeRobot бьёт каждую минуту → 43k записей в месяц только из-за этого):

```kotlin
filter { call ->
    val path = call.request.path()
    path.startsWith("/") && !path.startsWith("/health")
}
```

Body-logging при ошибках можно ограничить только 5xx (сейчас 4xx тоже логируется) — в `Monitoring.kt` поменять `status >= 400` на `status >= 500`.

Жёлтый баннер «Grafana Cloud Free includes a lot, but has some limitations» — это **постоянная реклама** Free плана, а не сигнал «лимит исчерпан». Реальное превышение лимита показывается отдельным конкретным сообщением.

---

## 9. С чего начать прямо сейчас

1. `curl /health` пару раз, чтобы было что показывать.
2. Explore → `{app="competra"} | json` за `Last 15 minutes`. Раскрой строку — убедись, что поля `call_id`, `http_status`, `duration_ms` извлекаются.
3. Если поля пустые — см. раздел Troubleshooting (нужен `LogstashLayout`).
4. Создай минимальный дашборд: `Logs (ERROR)`, `Time series (rps по статусу)`, `Stat (heartbeat)`.
5. Настрой алерт «приложение пропало» — это самое важное.
6. Остальное (p95, top paths, пользователи) — по мере необходимости.
