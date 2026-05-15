# Шпаргалка по эксплуатации сервера

Сервер: `/opt/competra`. Деплой через `docker compose` (новый CLI plugin, **без** дефиса) с двумя файлами:
- `docker-compose.yml` — базовый (используется локально, содержит `build: .`)
- `docker-compose.prod.yml` — override для прода (подменяет `build` на готовый `image: ghcr.io/...`)

Поэтому **на сервере всегда** запускай compose с обоими файлами:

```bash
cd /opt/competra
docker compose -f docker-compose.yml -f docker-compose.prod.yml <command>
```

Чтобы не писать длинное каждый раз, можно один раз в сессии задать алиас:

```bash
alias dc='docker compose -f /opt/competra/docker-compose.yml -f /opt/competra/docker-compose.prod.yml'
```

Дальше в шпаргалке использую `dc` для краткости. У тебя на сервере подставляй полную команду либо заведи постоянный алиас в `~/.bashrc`.

---

## Состояние

```bash
dc ps                              # статусы всех сервисов проекта
dc ps app                          # только app
docker ps                          # все контейнеры на сервере (включая чужие)
docker stats --no-stream           # CPU / RAM / сеть по контейнерам, одним снимком
docker compose top app             # процессы внутри контейнера app
```

---

## Логи

```bash
dc logs app                        # все логи app с момента старта контейнера
dc logs --tail=100 app             # последние 100 строк
dc logs -f app                     # follow (как tail -f), Ctrl+C для выхода
dc logs --since=10m app            # за последние 10 минут
dc logs --since=2026-05-14T13:00 app   # с указанной точки

# Поиск по ключевому слову
dc logs --tail=500 app | grep -iE 'error|exception|loki'

# Логи Postgres / RabbitMQ
dc logs --tail=100 postgres
dc logs --tail=100 rabbitmq
```

---

## Перезапуск и пересоздание

```bash
# Перезапустить контейнер БЕЗ перечитывания .env (старые env остаются)
dc restart app

# Пересоздать контейнер С перечитыванием .env (нужно после правки .env)
dc up -d --force-recreate app

# Подтянуть свежий образ из GHCR и пересоздать (после нового деплоя через CI)
dc pull app
dc up -d --force-recreate app

# Остановить (контейнер остаётся, можно стартануть обратно)
dc stop app
dc start app

# Снести и поднять заново (volumes сохраняются — данные БД целы)
dc down
dc up -d
```

**Важно:** `restart` НЕ перечитывает `.env`. Если поменял переменную окружения — нужен `up -d --force-recreate <service>`.

---

## Вход внутрь контейнера

```bash
dc exec app sh                     # shell внутри работающего контейнера app
dc exec app sh -c 'env | grep LOKI'   # одна команда без интерактива
dc exec postgres psql -U competra -d competra     # сразу в psql

# Если контейнер не запускается — посмотреть, что внутри образа, ничего не запуская
docker run --rm -it ghcr.io/<твой-репо>:latest sh
```

---

## Работа с базой

```bash
# Подключиться к psql
dc exec postgres psql -U competra -d competra

# Внутри psql полезное:
#   \dt          — список таблиц
#   \d users     — структура таблицы
#   \q           — выход

# Дамп БД на диск сервера
dc exec -T postgres pg_dump -U competra competra > /opt/competra/backup-$(date +%F).sql

# Восстановить из дампа (ОСТОРОЖНО — затирает текущие данные)
cat backup-2026-05-14.sql | dc exec -T postgres psql -U competra -d competra

# Скачать дамп с сервера себе на ноут (запустить на ноуте)
scp root@<server>:/opt/competra/backup-2026-05-14.sql ~/Downloads/
```

---

## Проверка переменных окружения и конфига

```bash
# Что реально видит контейнер
dc exec app sh -c 'env | sort'

# Конкретно Loki-переменные (длина токена вместо значения — безопасно)
dc exec app sh -c 'echo URL=$LOKI_URL; echo USER=$LOKI_USER_ID; echo KEY_len=${#LOKI_API_KEY}'

# Что подставит compose из .env перед запуском
dc config                          # печатает финальный merged YAML
dc config --services               # список сервисов
```

---

## Диагностика приложения

```bash
# Health-endpoint снаружи сервера
curl -i http://localhost:8080/health
curl -i http://localhost:8080/health/ready

# Health-endpoint изнутри (на случай, если порт 8080 закрыт фаерволом снаружи)
dc exec app sh -c 'wget -qO- http://localhost:8080/health'

# Что слушает контейнер
docker inspect competra-app-1 --format '{{json .NetworkSettings.Ports}}' | python3 -m json.tool

# Текущий образ, на котором поднят контейнер (полезно после деплоя)
docker inspect competra-app-1 --format '{{.Config.Image}}'

# Когда контейнер был последний раз запущен
docker inspect competra-app-1 --format '{{.State.StartedAt}}'
```

---

## Очистка диска

Docker склонен забивать диск старыми образами и логами. На VPS с маленьким диском быстро становится проблемой.

```bash
docker system df                   # сколько занято: images / containers / volumes / build cache

# Безопасная чистка: убрать только то, что точно не используется
docker system prune                # подтвердит, потом удалит остановленные контейнеры + dangling images
docker image prune                 # только висящие образы (без тегов)

# Агрессивная чистка — убрать ВСЕ неиспользуемые образы (не только dangling)
docker system prune -a             # ОСТОРОЖНО: удалит образы, не привязанные к запущенным контейнерам
                                   # после этого dc up без pull может не подняться, если образ был только локально

# Прибить build-кеш (docker buildx)
docker builder prune

# Логи контейнера сильно разрастаются? Truncate (контейнер должен быть запущен)
truncate -s 0 $(docker inspect --format='{{.LogPath}}' competra-app-1)
```

**На что НЕ делать `prune`:** на volumes (`docker volume prune`) — там хранятся данные Postgres и RabbitMQ. Удалишь — потеряешь всё.

---

## Загрузка свежего образа после деплоя CI

GitHub Actions пушит образ в `ghcr.io/<repo>:latest`. Чтобы применить новый код:

```bash
dc pull app                        # скачать свежий :latest
dc up -d --force-recreate app      # пересоздать с новым образом
dc logs -f app                     # убедиться, что стартанул нормально
```

Старые образы остаются на диске под другим digest — раз в неделю прогонять `docker image prune` чтобы не копились.

---

## Если что-то сломалось

```bash
# Не запускается? Посмотреть EXIT-код и причину
docker inspect competra-app-1 --format '{{.State.Status}} / {{.State.ExitCode}} / {{.State.Error}}'
dc logs --tail=200 app

# Зависло, не отвечает на ctrl+c?
docker kill competra-app-1           # SIGKILL контейнеру
dc up -d app

# Полный сброс к чистому состоянию (БД и RabbitMQ ОСТАЮТСЯ — volumes именные)
dc down
dc up -d

# Сбросить вообще всё, включая данные (УНИЧТОЖИТ БД!)
dc down -v                         # -v удаляет volumes — НИКОГДА на проде без бэкапа
```

---

## Сеть и порты

```bash
# Какие порты слушает сервер
ss -tlnp                           # современная замена netstat
ss -tlnp | grep 8080               # конкретно 8080

# Внутренняя docker-сеть проекта
docker network ls
docker network inspect competra_default

# DNS внутри docker-сети: app может обращаться к postgres по имени `postgres`
dc exec app sh -c 'nslookup postgres 2>/dev/null || getent hosts postgres'
```

---

## Ресурсы и нагрузка

```bash
docker stats                       # live: CPU/RAM/IO по всем контейнерам, Ctrl+C для выхода
docker stats --no-stream           # один снимок и сразу выход

# Свободное место на диске
df -h /
df -h /var/lib/docker              # где docker держит свои данные

# Top по памяти на хосте
free -h
ps aux --sort=-%mem | head -10
```

---

## Безопасные vs опасные команды

| Безопасно | Опасно — спрашивать дважды |
|---|---|
| `dc logs`, `dc ps`, `dc top` | `dc down -v` (удалит volumes — БД!) |
| `dc restart`, `dc up -d` | `docker volume rm <name>` |
| `dc pull`, `dc up -d --force-recreate` | `docker system prune -a --volumes` |
| `docker stats`, `docker inspect` | `docker exec -it postgres psql -c "DROP DATABASE ..."` |
| `dc exec app sh` (читать) | `> /opt/competra/.env` (перезапись без копии) |

Перед любой командой с `rm`, `prune -a`, `down -v` сделай бэкап БД (`pg_dump`) и `cp .env .env.bak`.

---

## Где что лежит на сервере

```
/opt/competra/
├── docker-compose.yml          # база (со старого build: .)
├── docker-compose.prod.yml     # override с image из GHCR
└── .env                        # секреты: DB_PASSWORD, JWT_SECRET, LOKI_*, S3_*, и т.д.

/var/lib/docker/
├── containers/<id>/<id>-json.log   # сырые логи контейнера (то, что показывает `docker logs`)
└── volumes/competra_postgres_data/   # данные Postgres
```

Бэкапить регулярно: **`.env`** (вручную, в защищённое место) и **дамп БД** (`pg_dump` по cron'у).

---

## Минимальный набор команд на каждый день

```bash
dc ps                                          # всё ли живо
dc logs --tail=50 app                          # последние строки
dc logs -f app                                 # follow при отладке
dc up -d --force-recreate app                  # применить новый .env
dc pull app && dc up -d --force-recreate app   # применить новый образ из CI
curl -i http://localhost:8080/health           # быстрый smoke-тест
```

Этого хватит для 90% операций.
