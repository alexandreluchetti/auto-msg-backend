#!/bin/sh
# Daily PostgreSQL backup (ARCHITECTURE section 15). Runs in the "backup" service of
# deploy/docker-compose.prod.yml, or by hand:
#   PGHOST=postgres PGUSER=conversa PGPASSWORD=... PGDATABASE=conversa ./backup.sh
#
# Writes /backups/conversa-<UTC timestamp>.dump (pg_dump custom format, compressed), checks that
# pg_restore can read it, keeps the last BACKUP_KEEP_DAYS days (default 14) and, when
# BACKUP_RCLONE_REMOTE is set (e.g. "s3:conversa-backups"), copies it off the server with rclone.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$BACKUP_DIR/conversa-$STAMP.dump"

mkdir -p "$BACKUP_DIR"
pg_dump --format=custom --compress=9 --no-owner --file="$FILE.partial"
pg_restore --list "$FILE.partial" > /dev/null
mv "$FILE.partial" "$FILE"
echo "backup written: $FILE ($(du -h "$FILE" | cut -f1))"

find "$BACKUP_DIR" -name 'conversa-*.dump' -mtime +"$KEEP_DAYS" -print -delete

if [ -n "${BACKUP_RCLONE_REMOTE:-}" ]; then
  rclone copy "$FILE" "$BACKUP_RCLONE_REMOTE"
  echo "backup copied to $BACKUP_RCLONE_REMOTE"
fi
