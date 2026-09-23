#!/usr/bin/env bash
# Готовит Postgres для процесса онлайн-трекинга (APP_MODE=tracking). Идемпотентен — безопасно
# запускать при каждом деплое (так и делает CI, см. .github/workflows/deploy.yml).
#
# Что делает:
#   1. Если в .env нет TRACKING_DB_PASSWORD / TRACKING_RO_DB_PASSWORD — генерирует их и дописывает.
#   2. Роль competra_tracking (CONNECTION LIMIT 10) — владелец отдельной БД competra_tracking.
#   3. Роль competra_tracking_ro (CONNECTION LIMIT 3, statement_timeout 3s, только чтение) —
#      SELECT на нужные трекингу таблицы основной БД.
#   4. Пароли ролей каждый раз приводятся к значениям из .env.
#
# Лимиты соединений — часть изоляции: даже при ошибке в трекинге он не сможет занять соединения
# Postgres, нужные основному приложению для приёма результатов.
#
# Использование (на VPS из корня проекта или локально):
#   ./scripts/setup_tracking_db.sh
#
# Соединение с БД — см. scripts/_db_common.sh (.env подхватывается автоматически).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

TRACKING_DB="competra_tracking"
TRACKING_ROLE="competra_tracking"
TRACKING_RO_ROLE="competra_tracking_ro"
# Таблицы основной БД, которые трекинг читает при старте сессии.
MAIN_TABLES="orienteering_participants, participant_groups, distances, competitions, orienteering_competitions, orienteering_results"

# Дописывает в .env сгенерированный пароль, если переменной там ещё нет.
ensure_password() {
    local var="$1"
    if [[ -f "$ENV_FILE" ]] && grep -q "^${var}=." "$ENV_FILE"; then
        return
    fi
    local generated
    if command -v openssl >/dev/null 2>&1; then
        generated="$(openssl rand -hex 24)"
    else
        generated="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    fi
    echo "${var}=${generated}" >> "$ENV_FILE"
    echo "Сгенерирован ${var} и дописан в ${ENV_FILE}"
}

ensure_password TRACKING_DB_PASSWORD
ensure_password TRACKING_RO_DB_PASSWORD

# shellcheck disable=SC1091
source "$SCRIPT_DIR/_db_common.sh"
# _db_common.sh не перетирает уже выставленные переменные — перечитываем пароли явно.
TRACKING_DB_PASSWORD="$(grep "^TRACKING_DB_PASSWORD=" "$ENV_FILE" | tail -n1 | cut -d= -f2-)"
TRACKING_RO_DB_PASSWORD="$(grep "^TRACKING_RO_DB_PASSWORD=" "$ENV_FILE" | tail -n1 | cut -d= -f2-)"

MAIN_DB="$PGDATABASE"

# Экранирует строку для SQL-литерала в одинарных кавычках.
sql_literal() {
    printf "'%s'" "${1//\'/\'\'}"
}

echo "Postgres: $PGDATABASE (user=$PGUSER, mode=$(resolve_mode))"

echo "→ Роли"
for role in "$TRACKING_ROLE" "$TRACKING_RO_ROLE"; do
    run_psql "DO \$\$ BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') THEN
            CREATE ROLE $role LOGIN;
        END IF;
    END \$\$;" -q
done
run_psql "ALTER ROLE $TRACKING_ROLE WITH LOGIN CONNECTION LIMIT 10 PASSWORD $(sql_literal "$TRACKING_DB_PASSWORD");" -q
run_psql "ALTER ROLE $TRACKING_RO_ROLE WITH LOGIN CONNECTION LIMIT 3 PASSWORD $(sql_literal "$TRACKING_RO_DB_PASSWORD");" -q
run_psql "ALTER ROLE $TRACKING_RO_ROLE SET statement_timeout = '3s';" -q
run_psql "ALTER ROLE $TRACKING_RO_ROLE SET default_transaction_read_only = on;" -q

echo "→ БД $TRACKING_DB"
# CREATE DATABASE нельзя выполнить внутри DO-блока — проверяем существование отдельным запросом.
if [[ -z "$(run_psql "SELECT 1 FROM pg_database WHERE datname = '$TRACKING_DB';" -tA)" ]]; then
    run_psql "CREATE DATABASE $TRACKING_DB OWNER $TRACKING_ROLE;" -q
    echo "  создана"
else
    echo "  уже есть"
fi
# В БД трекинга ходит только её владелец (и суперпользователь).
run_psql "REVOKE CONNECT ON DATABASE $TRACKING_DB FROM PUBLIC;" -q
run_psql "GRANT CONNECT ON DATABASE $TRACKING_DB TO $TRACKING_ROLE;" -q

echo "→ Права только на чтение в $MAIN_DB"
run_psql "GRANT CONNECT ON DATABASE $MAIN_DB TO $TRACKING_RO_ROLE;" -q
run_psql "GRANT USAGE ON SCHEMA public TO $TRACKING_RO_ROLE;" -q
run_psql "GRANT SELECT ON $MAIN_TABLES TO $TRACKING_RO_ROLE;" -q

echo "Готово."
