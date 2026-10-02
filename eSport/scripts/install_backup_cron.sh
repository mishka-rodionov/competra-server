#!/usr/bin/env bash
# Ставит (или обновляет) ежедневный бэкап БД в crontab текущего пользователя. Идемпотентен —
# безопасно запускать при каждом деплое (так и делает CI, см. .github/workflows/deploy.yml).
#
# Строка crontab помечается маркером — при повторном запуске старая строка заменяется новой,
# дубликатов не появляется. Остальные записи crontab не трогаются.
#
# Использование (на VPS из корня проекта):
#   ./scripts/install_backup_cron.sh                       # ежедневно в 03:00 по времени сервера
#   BACKUP_CRON_SCHEDULE="0 4 * * 1" ./scripts/install_backup_cron.sh
#
# Время — по часовому поясу сервера (cron не знает про часовой пояс приложения).
# Посмотреть установленное: crontab -l | grep competra-backup
# Лог запусков:             tail -n 50 backups/backup.log

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SCHEDULE="${BACKUP_CRON_SCHEDULE:-0 3 * * *}"
MARKER="# competra-backup"
BACKUP_DIR="$PROJECT_ROOT/backups"
LOG_FILE="$BACKUP_DIR/backup.log"

if ! command -v crontab >/dev/null 2>&1; then
    echo "[error] crontab не найден. Установите cron (apt install cron) и повторите." >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# PATH задаём явно: у cron он урезан до /usr/bin:/bin, а docker бывает в /usr/local/bin.
# flock -n — если предыдущий дамп ещё идёт, новый не стартует поверх него.
JOB="$SCHEDULE PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin flock -n /tmp/competra-backup.lock $SCRIPT_DIR/backup_db.sh >> $LOG_FILE 2>&1 $MARKER"

# crontab -l падает с ненулевым кодом, если crontab ещё пуст — это не ошибка.
CURRENT="$(crontab -l 2>/dev/null || true)"
WITHOUT_OURS="$(printf '%s\n' "$CURRENT" | grep -vF "$MARKER" || true)"

{
    if [[ -n "$WITHOUT_OURS" ]]; then
        printf '%s\n' "$WITHOUT_OURS"
    fi
    printf '%s\n' "$JOB"
} | crontab -

echo "Cron бэкапа БД: $SCHEDULE (лог: $LOG_FILE)"
