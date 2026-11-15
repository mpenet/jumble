#!/bin/sh
# Checks database backups end to end against the docker compose Garage: runs
# the image, lets it write, deletes its volume, and checks the next start
# restores the database from the replica. Usage: dev/backup-restore-check.sh [image]
set -eu

image=${1:-jumble:dev}
run_id=$(date +%s)
volume="jumble-check-$run_id"
backup_path="check-$run_id"

run() { # $1 currency
  docker run -d --name "jumble-check-$run_id" --network jumble_default -p 127.0.0.1:18090:8080 \
    -v "$volume:/data" \
    --cap-drop ALL --security-opt no-new-privileges:true \
    --read-only --tmpfs /tmp:rw,exec,nosuid,size=256m \
    -e JUMBLE_BASE_URL=http://localhost:18090 \
    -e JUMBLE_ALLOW_INSECURE_URLS=true \
    -e JUMBLE_SIGN_IN=oidc -e JUMBLE_ALLOWED_EMAILS='*' \
    -e JUMBLE_OIDC_ISSUER=http://dex:5556/dex -e JUMBLE_OIDC_CLIENT_SECRET=check \
    -e JUMBLE_S3_ENDPOINT=http://garage:3900 -e JUMBLE_S3_REGION=garage -e JUMBLE_S3_BUCKET=jumble \
    -e JUMBLE_S3_ACCESS_KEY=GK0123456789abcdef01234567 \
    -e JUMBLE_S3_SECRET_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
    -e JUMBLE_BACKUP_ACCESS_KEY=GK0123456789abcdef01234567 \
    -e JUMBLE_BACKUP_SECRET_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
    -e JUMBLE_BACKUP_BUCKET=jumble-backup -e JUMBLE_BACKUP_PATH="$backup_path" \
    -e JUMBLE_SMTP_HOST=mailpit -e JUMBLE_SMTP_PORT=1025 -e JUMBLE_SMTP_STARTTLS=false \
    -e JUMBLE_MAIL_FROM=jumble@example.com \
    -e JUMBLE_CURRENCY="$1" \
    "$image" >/dev/null
}

healthy() {
  for _ in $(seq 1 60); do
    curl -sf -o /dev/null http://127.0.0.1:18090/healthz && return 0
    sleep 1
  done
  return 1
}

stop() {
  docker stop "jumble-check-$run_id" >/dev/null
  logs=$(docker logs "jumble-check-$run_id" 2>&1)
  exit_code=$(docker inspect -f '{{.State.ExitCode}}' "jumble-check-$run_id")
  docker rm "jumble-check-$run_id" >/dev/null
  docker volume rm "$volume" >/dev/null
}

fail() { echo "FAIL: $1"; echo "$logs" | tail -20; exit 1; }

# Whatever happens, leave no container or volume behind.
cleanup() {
  docker rm -f "jumble-check-$run_id" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "1. fresh start writes the database and replicates it"
run CHF
healthy || { stop; fail "first start did not become healthy"; }
sleep 3
stop
echo "$logs" | grep -q "no replica yet, starting with a new database" || fail "expected a new database"
echo "$logs" | grep -q "s-exp.jumble - Started" || fail "application did not start"
[ "$exit_code" = 0 ] || fail "stopping exited with $exit_code instead of 0"

echo "2. with the volume gone, the next start restores the database"
run CHF
healthy || { stop; fail "restored start did not become healthy"; }
stop
echo "$logs" | grep -q "database restored from the replica" || fail "the database was not restored"

echo "3. the restored database is the real one: it refuses a different currency"
run EUR
sleep 15
stop
echo "$logs" | grep -q "differs from the one this database was created with" \
  || fail "a different currency was accepted, so the database was not restored"

echo "OK: backups restore"
