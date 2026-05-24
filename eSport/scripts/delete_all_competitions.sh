#!/usr/bin/env bash
# Удаляет ВСЕ соревнования и связанные данные.
#
# Очищаются таблицы:
#   competitions, orienteering_competitions, distances,
#   participant_groups, orienteering_participants,
#   orienteering_results, split_times
#
# Использование:
#   ./scripts/delete_all_competitions.sh           # с интерактивным подтверждением
#   ./scripts/delete_all_competitions.sh --yes     # без подтверждения
#
# Переменные окружения см. в scripts/_db_common.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_db_common.sh"

if [[ "${1:-}" == "--yes" || "${1:-}" == "-y" ]]; then
    export ASSUME_YES=1
fi

echo "БД:   $PGDATABASE @ $PGHOST:$PGPORT (user=$PGUSER)"
echo "Действие: TRUNCATE всех таблиц соревнований."
if ! confirm "Точно удалить все соревнования?"; then
    echo "Отменено."
    exit 0
fi

# TRUNCATE ... RESTART IDENTITY сбрасывает SERIAL-последовательности у competitions/distances/participant_groups.
run_psql "
TRUNCATE TABLE
    split_times,
    orienteering_results,
    orienteering_participants,
    participant_groups,
    distances,
    orienteering_competitions,
    competitions
RESTART IDENTITY;
"

echo "Готово. Все соревнования и связанные данные удалены."
