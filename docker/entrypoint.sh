#!/bin/sh
# Restores the database from its replica when the volume has none (first start
# or a lost volume), then runs the application under Litestream, which
# replicates every change and exits when the application does.
set -eu

: "${JUMBLE_DB_PATH:?}"
: "${JUMBLE_BACKUP_BUCKET:?JUMBLE_BACKUP_BUCKET is required}"
: "${JUMBLE_BACKUP_PATH:?}"
: "${JUMBLE_BACKUP_ACCESS_KEY:?JUMBLE_BACKUP_ACCESS_KEY is required}"
: "${JUMBLE_BACKUP_SECRET_KEY:?JUMBLE_BACKUP_SECRET_KEY is required}"

# An empty file is no database: restore over it.
if [ -s "$JUMBLE_DB_PATH" ]; then
  echo "entrypoint: database present on the volume"
else
  echo "entrypoint: no database on the volume, restoring from the replica if there is one"
  # Bounded: storage that cannot be reached must not hang the start silently.
  if ! timeout 300 litestream restore -config /etc/litestream.yml -if-replica-exists "$JUMBLE_DB_PATH"; then
    echo "entrypoint: restoring failed or took over 5 minutes; check the backup settings" >&2
    exit 1
  fi
  if [ -s "$JUMBLE_DB_PATH" ]; then
    echo "entrypoint: database restored from the replica"
  else
    echo "entrypoint: no replica yet, starting with a new database"
  fi
fi

# JAVA_OPTS adds to the options Jumble needs, and can override the memory one.
exec litestream replicate -config /etc/litestream.yml \
  -exec "java --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError ${JAVA_OPTS:-} -jar /app/jumble.jar"
