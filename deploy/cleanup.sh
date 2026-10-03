#!/usr/bin/env bash
# Free disk space on the VM. Safe to run any time; nothing here touches the newest show.
#
#   ./deploy/cleanup.sh          # delete shows older than 24 hours (and their seats/reservations), prune Docker, trim old recordings
#   ./deploy/cleanup.sh 2        # same, but shows older than 2 hours
#   ./deploy/cleanup.sh 0        # everything except the newest show
#
# Local use:  COMPOSE="docker compose" ./deploy/cleanup.sh 0
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE="${COMPOSE:-docker compose -f docker-compose.aws.yml}"
HOURS="${1:-24}"
[[ "$HOURS" =~ ^[0-9]+$ ]] || { echo "hours must be a whole number"; exit 2; }

echo "== disk before"; df -h / | tail -1

echo "== deleting shows older than ${HOURS}h (keeping the newest show)"
$COMPOSE exec -T db psql -U postgres -d seats -v ON_ERROR_STOP=1 <<SQL
BEGIN;
CREATE TEMP TABLE old_shows AS
  SELECT id FROM shows
   WHERE created_at < now() - interval '${HOURS} hours'
     AND id NOT IN (SELECT id FROM shows ORDER BY created_at DESC LIMIT 1);
DELETE FROM user_show_quota WHERE show_id IN (SELECT id FROM old_shows);
DELETE FROM reservations    WHERE show_id IN (SELECT id FROM old_shows);
DELETE FROM seats           WHERE show_id IN (SELECT id FROM old_shows);
DELETE FROM shows           WHERE id      IN (SELECT id FROM old_shows);
COMMIT;
SQL
# VACUUM cannot run inside a transaction block, so it is a separate call
$COMPOSE exec -T db psql -U postgres -d seats -c "VACUUM (ANALYZE)"

echo "== pruning unused Docker images / build cache"
docker image prune -f >/dev/null
docker builder prune -f --filter "until=24h" >/dev/null || true

echo "== deleting recorded log files older than 7 days"
find "$HOME/logs" -type f -mtime +7 -delete 2>/dev/null || true

echo "== disk after"; df -h / | tail -1
$COMPOSE exec -T db psql -U postgres -d seats -At -c \
  "SELECT 'shows='||(SELECT count(*) FROM shows)||' seats='||(SELECT count(*) FROM seats)||' reservations='||(SELECT count(*) FROM reservations)"
