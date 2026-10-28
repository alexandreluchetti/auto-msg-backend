#!/bin/sh
# Entry point of the backup container: one backup at start, then every day at BACKUP_TIME (UTC,
# HH:MM, default 06:30 = 03:30 in São Paulo).
set -eu

TIME="${BACKUP_TIME:-06:30}"
/scripts/backup.sh
while true; do
  now=$(date -u +%s)
  next=$(date -u -d "$(date -u +%Y-%m-%d) $TIME" +%s)
  [ "$next" -le "$now" ] && next=$((next + 86400))
  sleep $((next - now))
  /scripts/backup.sh || echo "backup failed" >&2
done
