#!/usr/bin/env bash
# Удаляет одно соревнование по ID и все связанные с ним данные.
#
# Очищаются строки в:
#   split_times (через result_id → orienteering_results),
#   orienteering_results, orienteering_participants,
#   participant_groups, distances, orienteering_competitions,
#   и сама запись из competitions.
#
# Использование:
#   ./scripts/delete_competition.sh <competition_id>
#   ./scripts/delete_competition.sh <competition_id> --yes
#
# Переменные окружения см. в scripts/_db_common.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_db_common.sh"

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <competition_id> [--yes]" >&2
    exit 1
fi

COMPETITION_ID="$1"
shift || true

if [[ "${1:-}" == "--yes" || "${1:-}" == "-y" ]]; then
    export ASSUME_YES=1
fi

# Валидация: competitions.id это BIGINT.
if ! [[ "$COMPETITION_ID" =~ ^[0-9]+$ ]]; then
    echo "[error] competition_id должен быть целым числом, получено: '$COMPETITION_ID'" >&2
    exit 1
fi

echo "БД:   $PGDATABASE @ $PGHOST:$PGPORT (user=$PGUSER)"
echo "Цель: удалить соревнование id=$COMPETITION_ID и все связанные данные."

# Проверяем, что соревнование существует, и показываем краткую инфу.
EXISTS_SQL="SELECT id, title, start_date FROM competitions WHERE id = $COMPETITION_ID;"
run_psql "$EXISTS_SQL"

if ! confirm "Удалить это соревнование?"; then
    echo "Отменено."
    exit 0
fi

# Удаление в одной транзакции, в порядке от листьев к корню.
# split_times связано с результатами через result_id (VARCHAR), внешних ключей в схеме нет.
run_psql "
BEGIN;

DELETE FROM split_times
WHERE result_id IN (
    SELECT id FROM orienteering_results WHERE competition_id = $COMPETITION_ID
);

DELETE FROM orienteering_results       WHERE competition_id = $COMPETITION_ID;
DELETE FROM orienteering_participants  WHERE competition_id = $COMPETITION_ID;
DELETE FROM participant_groups         WHERE competition_id = $COMPETITION_ID;
DELETE FROM distances                  WHERE competition_id = $COMPETITION_ID;
DELETE FROM orienteering_competitions  WHERE competition_id = $COMPETITION_ID;
DELETE FROM competitions               WHERE id = $COMPETITION_ID;

COMMIT;
"

echo "Готово. Соревнование $COMPETITION_ID и связанные данные удалены."
