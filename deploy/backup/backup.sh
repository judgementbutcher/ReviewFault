#!/bin/sh
set -eu
umask 077

until restic snapshots >/dev/null 2>&1; do
  restic init >/dev/null 2>&1 || true
  sleep 5
done

while true; do
  dump="/tmp/reviewfault-$(date -u +%Y%m%dT%H%M%SZ).dump"
  pg_dump --format=custom --file="$dump"
  restic backup "$dump" /object-data
  restic forget --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
  rm -f "$dump"
  sleep 86400
done
