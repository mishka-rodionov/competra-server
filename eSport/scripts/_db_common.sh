#!/usr/bin/env bash
# Общий хелпер для скриптов администрирования БД.
# Подгружает .env, выбирает между psql внутри docker-контейнера и локальным psql.
#
# Использование (из другого скрипта):
#   source "$(dirname "$0")/_db_common.sh"
#   run_psql "SQL ..." [extra psql args]
#
# Переопределяемые переменные окружения:
#   DB_MODE      = auto | docker | local           (по умолчанию: auto)
#   PG_CONTAINER = имя контейнера                  (по умолчанию: postgres)
#   PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD     (принудительно переопределяют
#                                                    всё, что скрипт бы вычислил сам)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Явные PGUSER/PGDATABASE из вызывающего окружения (до подгрузки .env) имеют
# наивысший приоритет — запоминаем их, чтобы .env и авто-детект контейнера их не затёрли.
_EXPLICIT_PGUSER="${PGUSER:-}"
_EXPLICIT_PGDATABASE="${PGDATABASE:-}"

# Подгружаем .env, если он есть, не перетирая уже выставленные переменные.
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/.env"
    set +a
fi

DB_MODE="${DB_MODE:-auto}"
PG_CONTAINER="${PG_CONTAINER:-postgres}"

# Находит контейнер сервиса postgres ИМЕННО этого compose-проекта (через `docker compose
# ps`, привязано к compose-файлам в PROJECT_ROOT), а не по угадыванию имени через
# `docker ps | grep`. Это важно: на сервере может годами висеть посторонний контейнер
# с "postgres" в имени (от старого/дублирующего деплоя) — grep по подстроке рискует
# схватить его вместо настоящего, рабочего контейнера. Если docker compose недоступен
# или не находит сервис (например, запуск не из клона репозитория) — падаем обратно на
# grep по PG_CONTAINER как раньше.
resolve_container() {
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        local compose_files=(-f "$PROJECT_ROOT/docker-compose.yml")
        if [[ -f "$PROJECT_ROOT/docker-compose.prod.yml" ]]; then
            compose_files+=(-f "$PROJECT_ROOT/docker-compose.prod.yml")
        fi
        local via_compose=""
        via_compose="$(docker compose "${compose_files[@]}" ps -q postgres 2>/dev/null | head -n1 || true)"
        if [[ -n "$via_compose" ]]; then
            docker inspect --format '{{.Name}}' "$via_compose" 2>/dev/null | sed 's#^/##'
            return
        fi
    fi
    # Фолбэк: угадывание по имени (старое поведение).
    docker ps --format '{{.Names}}' 2>/dev/null | grep "${PG_CONTAINER}" | head -n1
}

# Режим и контейнер вычисляются один раз и кэшируются — они не меняются в течение
# работы скрипта, а повторные docker-вызовы на каждый run_psql лишние и могут дать
# рассинхронизацию, если что-то в системе поменялось между вызовами.
_RESOLVED_MODE=""
_RESOLVED_CONTAINER=""

resolve_mode() {
    if [[ -n "$_RESOLVED_MODE" ]]; then
        echo "$_RESOLVED_MODE"; return
    fi
    if [[ "$DB_MODE" != "auto" ]]; then
        _RESOLVED_MODE="$DB_MODE"
    elif command -v docker >/dev/null 2>&1; then
        _RESOLVED_CONTAINER="$(resolve_container || true)"
        if [[ -n "$_RESOLVED_CONTAINER" ]]; then
            _RESOLVED_MODE="docker"
        else
            _RESOLVED_MODE="local"
        fi
    else
        _RESOLVED_MODE="local"
    fi
    echo "$_RESOLVED_MODE"
}

get_container() {
    if [[ -z "$_RESOLVED_CONTAINER" ]]; then
        _RESOLVED_CONTAINER="$(resolve_container || true)"
    fi
    echo "$_RESOLVED_CONTAINER"
}

# Парсим DB_URL (jdbc:postgresql://host:port/db) в PG* переменные, если они не заданы.
# Используется только как фолбэк для local-режима — в docker-режиме источник истины
# ниже (реальные POSTGRES_USER/POSTGRES_DB контейнера), т.к. значения из .env могут
# быть устаревшими и не совпадать с тем, чем реально инициализирован конкретный контейнер.
parse_db_url() {
    local url="${DB_URL:-}"
    if [[ -z "$url" ]]; then return 0; fi
    local stripped="${url#jdbc:postgresql://}"
    local hostport="${stripped%%/*}"
    local db="${stripped#*/}"
    db="${db%%\?*}"
    PGHOST="${PGHOST:-${hostport%%:*}}"
    PGPORT="${PGPORT:-${hostport##*:}}"
    PGDATABASE="${PGDATABASE:-$db}"
}

MODE="$(resolve_mode)"

CONTAINER_PGUSER=""
CONTAINER_PGDATABASE=""
if [[ "$MODE" == "docker" ]]; then
    _container="$(get_container)"
    if [[ -n "$_container" ]]; then
        _env_dump="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$_container" 2>/dev/null || true)"
        CONTAINER_PGUSER="$(echo "$_env_dump" | grep '^POSTGRES_USER=' | head -n1 | cut -d= -f2- || true)"
        CONTAINER_PGDATABASE="$(echo "$_env_dump" | grep '^POSTGRES_DB=' | head -n1 | cut -d= -f2- || true)"
    fi
fi

parse_db_url

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
# Приоритет: явно заданный PGUSER/PGDATABASE вызывающим > реальные значения из
# окружения найденного docker-контейнера > DB_USER/DB_URL из .env > дефолт "competra".
PGUSER="${_EXPLICIT_PGUSER:-${CONTAINER_PGUSER:-${DB_USER:-competra}}}"
PGDATABASE="${_EXPLICIT_PGDATABASE:-${CONTAINER_PGDATABASE:-competra}}"
PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"

export PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD

run_psql() {
    local sql="$1"; shift || true
    local mode
    mode="$(resolve_mode)"

    if [[ "$mode" == "docker" ]]; then
        # Внутри контейнера PGPASSWORD/PGUSER/PGDATABASE прокидываются явно.
        local container=""
        container="$(get_container)"
        if [[ -z "$container" ]]; then
            echo "[error] Не найден контейнер postgres (ни через docker compose, ни по имени '${PG_CONTAINER}')." >&2
            exit 1
        fi
        docker exec -i \
            -e PGPASSWORD="$PGPASSWORD" \
            "$container" \
            psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE" "$@" -c "$sql"
    else
        if ! command -v psql >/dev/null 2>&1; then
            echo "[error] psql не найден в PATH, а docker-контейнер '$PG_CONTAINER' не запущен." >&2
            echo "        Установите psql (brew install libpq) или запустите docker-compose up postgres." >&2
            exit 1
        fi
        psql -v ON_ERROR_STOP=1 -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" "$@" -c "$sql"
    fi
}

# Yes/No подтверждение. Можно обойти флагом --yes.
confirm() {
    local prompt="$1"
    if [[ "${ASSUME_YES:-0}" == "1" ]]; then
        return 0
    fi
    read -r -p "$prompt [y/N]: " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]]
}
