#!/usr/bin/env bash
# Proves a Jumble's backups restore: on its server, restores the latest backup
# into a scratch volume with the running image and its settings, then checks
# the copy (SQLite's integrity check, the schema version and the rows of the
# main tables, against the live database). The running Jumble and its volume
# are only read; the scratch volume is removed however it ends.
#
# With INSTANCE, deploy.env there names the server (HOST); otherwise HOST is
# given. Exits 0 when the restored copy matches the live database.
#
#   INSTANCE=deploy/instances/community deploy/restore-drill.sh
#   HOST=jumble-admin@jumble-server deploy/restore-drill.sh
set -euo pipefail

if [ -n "${INSTANCE:-}" ] && [ -z "${HOST:-}" ]; then
  test -f "$INSTANCE/deploy.env" || { echo "restore-drill: $INSTANCE/deploy.env is missing" >&2; exit 1; }
  # shellcheck source=/dev/null # the instance's own settings, on this machine
  . "$INSTANCE/deploy.env"
fi
: "${HOST:?the server, as user@address}"

ssh "$HOST" sudo bash -s <<'EOF'
set -euo pipefail
image=$(sed -n 's/^Image=//p' /etc/containers/systemd/jumble.container)
[ -n "$image" ] || { echo "restore-drill: no Jumble installed on this server" >&2; exit 1; }
volume="jumble-drill-$(date +%s)"
trap 'podman volume rm -f "$volume" >/dev/null 2>&1 || true' EXIT
podman volume create "$volume" >/dev/null

echo "restore-drill: restoring the latest backup, with $image"
start=$(date +%s)
# Mounted where the image keeps its data, the new volume takes that
# directory's owner, the unprivileged user the image runs as.
podman run --rm --env-file /etc/jumble/jumble.env -v "$volume:/data" --entrypoint litestream "$image" \
  restore -config /etc/litestream.yml /data/jumble.db
echo "restore-drill: restored in $(($(date +%s) - start)) s"

# Both databases are in WAL mode, which SQLite cannot read from a read-only
# mount: they are mounted as they are and opened read-only.
podman run --rm -v "$volume:/drill" -v jumble-data:/data --entrypoint java "$image" \
  --enable-native-access=ALL-UNNAMED -cp /app/jumble.jar clojure.main -e '
(require (quote [next.jdbc :as jdbc]))
(letfn [(source [path] (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:file:" path "?mode=ro")}))
        (one [db sql] (val (first (jdbc/execute-one! db [sql]))))
        (rows [db] (into (sorted-map) (for [t ["user" "listing" "photo" "thread" "message" "email_outbox"]]
                                        [t (one db (str "SELECT count(*) FROM " t))])))]
  (let [restored (source "/drill/jumble.db")
        live (source "/data/jumble.db")
        integrity (one restored "PRAGMA integrity_check")
        versions [(one restored "PRAGMA user_version") (one live "PRAGMA user_version")]
        counts [(rows restored) (rows live)]]
    (println "restore-drill: integrity" integrity)
    (println "restore-drill: schema version, restored and live:" versions)
    (println "restore-drill: rows restored" (first counts))
    (println "restore-drill: rows live    " (second counts))
    (cond
      (not= "ok" integrity) (do (println "restore-drill: FAILED, the restored database is damaged") (System/exit 1))
      (not (apply = versions)) (do (println "restore-drill: FAILED, the backup is of another schema version") (System/exit 1))
      (not (apply = counts)) (do (println "restore-drill: FAILED, rows differ (changed during the drill? run it again)") (System/exit 1))
      :else (do (println "restore-drill: passed") nil))))'
EOF
