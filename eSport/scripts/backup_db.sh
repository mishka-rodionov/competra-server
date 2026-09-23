#!/usr/bin/env bash
# Делает полный дамп продовой БД прямо на сервере (VPS), в custom-формате pg_dump
# (сжатый, восстанавливается через pg_restore, поддерживает выборочное восстановление
# по таблицам).
#
# Скрипт выполняется НА СЕРВЕРЕ (после ssh на VPS), результат кладётся в
# scripts/../backups/ рядом с проектом. Чтобы забрать дамп на локальную машину,
# после завершения скрипт печатает готовую команду scp — выполните её уже на
# локальной машине (не на сервере).
#
# Использование (на VPS, из корня проекта eSport):
#   ./scripts/backup_db.sh                # обычный дамп
#   KEEP_DAYS=30 ./scripts/backup_db.sh    # хранить локальные дампы 30 дней вместо 14
#
# Переменные окружения соединения с БД — см. scripts/_db_common.sh (.env подхватывается
# автоматически). Дополнительно:
#   BACKUP_DIR = каталог для дампов (по умолчанию: <project_root>/backups)
#   KEEP_DAYS  = сколько дней хранить старые дампы локально на сервере (по умолчанию: 14)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_db_common.sh"

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_ROOT/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

mkdir -p "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
DUMP_FILE="$BACKUP_DIR/competra_${TIMESTAMP}.dump"

echo "БД:     $PGDATABASE @ $PGHOST:$PGPORT (user=$PGUSER)"
echo "Файл:   $DUMP_FILE"

MODE="$(resolve_mode)"

if [[ "$MODE" == "docker" ]]; then
    CONTAINER="$(get_container || true)"
    if [[ -z "$CONTAINER" ]]; then
        echo "[error] Не найден контейнер postgres (ни через docker compose, ни по имени '${PG_CONTAINER}')." >&2
        exit 1
    fi
    echo "Режим:  docker (контейнер: $CONTAINER)"
    docker exec -e PGPASSWORD="$PGPASSWORD" "$CONTAINER" \
        pg_dump -U "$PGUSER" -d "$PGDATABASE" -F c > "$DUMP_FILE"
else
    echo "Режим:  local (psql/pg_dump на хосте)"
    if ! command -v pg_dump >/dev/null 2>&1; then
        echo "[error] pg_dump не найден в PATH." >&2
        echo "        Установите postgresql-client или запустите через docker (DB_MODE=docker)." >&2
        exit 1
    fi
    PGPASSWORD="$PGPASSWORD" pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -F c -f "$DUMP_FILE"
fi

SIZE="$(du -h "$DUMP_FILE" | cut -f1)"
echo "Готово: $DUMP_FILE ($SIZE)"

# Чистим локальные дампы на сервере старше KEEP_DAYS, чтобы диск не переполнялся —
# после того как дамп забран на локальную машину, старые копии на VPS не нужны.
DELETED_COUNT="$(find "$BACKUP_DIR" -name 'competra_*.dump' -mtime +"$KEEP_DAYS" -print -delete | wc -l | tr -d ' ')"
if [[ "$DELETED_COUNT" -gt 0 ]]; then
    echo "Удалено старых дампов (старше ${KEEP_DAYS}д): $DELETED_COUNT"
fi

echo
echo "Чтобы забрать дамп на локальную машину, выполните ТАМ (не на сервере):"
echo "  scp <ssh_user>@<vps_host>:$DUMP_FILE ./"
echo
echo "Восстановление из дампа (на любой машине с доступом к целевой БД):"
echo "  pg_restore -h <host> -U <user> -d <database> --clean --if-exists <файл.dump>"
