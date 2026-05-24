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
#   PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD     (для local-режима)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Подгружаем .env, если он есть, не перетирая уже выставленные переменные.
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/.env"
    set +a
fi

DB_MODE="${DB_MODE:-auto}"
PG_CONTAINER="${PG_CONTAINER:-postgres}"

# Парсим DB_URL (jdbc:postgresql://host:port/db) в PG* переменные, если они не заданы.
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

parse_db_url

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-${DB_USER:-competra}}"
PGDATABASE="${PGDATABASE:-competra}"
PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"

export PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD

# Авто-выбор режима: пробуем docker, если контейнер с postgres работает.
resolve_mode() {
    if [[ "$DB_MODE" != "auto" ]]; then
        echo "$DB_MODE"; return
    fi
    if command -v docker >/dev/null 2>&1 \
       && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx ".*${PG_CONTAINER}.*"; then
        echo "docker"
    else
        echo "local"
    fi
}

run_psql() {
    local sql="$1"; shift || true
    local mode
    mode="$(resolve_mode)"

    if [[ "$mode" == "docker" ]]; then
        # Внутри контейнера PGPASSWORD/PGUSER/PGDATABASE прокидываются явно.
        local container
        container="$(docker ps --format '{{.Names}}' | grep "${PG_CONTAINER}" | head -n1)"
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
