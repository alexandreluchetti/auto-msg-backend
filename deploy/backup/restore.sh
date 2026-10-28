#!/bin/sh
# Restores a backup written by backup.sh into a database (default: a new "conversa_restore", so
# the live one is untouched; check it, then switch over or copy what is needed).
#   PGHOST=postgres PGUSER=conversa PGPASSWORD=... ./restore.sh /backups/conversa-<stamp>.dump [database]
set -eu

FILE="${1:?usage: restore.sh <file.dump> [database]}"
TARGET="${2:-conversa_restore}"

createdb "$TARGET"
pg_restore --no-owner --exit-on-error --dbname="$TARGET" "$FILE"
psql --dbname="$TARGET" --tuples-only --command \
  "SELECT 'restored ' || count(*) || ' tenants, ' || (SELECT count(*) FROM customer_order) || ' orders' FROM tenant"
