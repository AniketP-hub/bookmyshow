#!/usr/bin/env bash
# Record the app's structured logs to a file while you run the burst, then print a summary.
#
#   ./deploy/record-logs.sh            # records until you press Ctrl+C
#   ./deploy/record-logs.sh 120        # records for 120 seconds
#
# Start it BEFORE the burst (in a second SSH window), run the burst from your laptop, then stop it.
# Output: ~/logs/app-<timestamp>.jsonl  and  ~/logs/app-<timestamp>.summary.txt
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p "$HOME/logs"
TS=$(date +%Y%m%d-%H%M%S)
OUT="$HOME/logs/app-$TS.jsonl"
COMPOSE="docker compose -f docker-compose.aws.yml"

echo "Recording app logs to $OUT  (Ctrl+C to stop${1:+, auto-stop after ${1}s})"
if [ -n "${1:-}" ]; then
  timeout "$1" $COMPOSE logs -f --no-log-prefix --since 0s app > "$OUT" || true
else
  trap 'true' INT   # let Ctrl+C stop the log stream but continue to the summary below
  $COMPOSE logs -f --no-log-prefix --since 0s app > "$OUT" || true
fi

echo
echo "Saved $(wc -l < "$OUT") lines. Summary:"
python3 deploy/log-summary.py "$OUT" | tee "${OUT%.jsonl}.summary.txt"
