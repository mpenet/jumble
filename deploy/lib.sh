# What every provider's provision.sh and teardown.sh share, sourced by them:
# the instance's directory on this machine, its storage keys and settings,
# the backup bucket's lifecycle, and preparing its server with bootstrap.sh.
# shellcheck shell=bash
# The script sets env_file, its instance's jumble.env, before using the
# functions reading or writing it.
# shellcheck disable=SC2154

# The script's name in what it prints: provision or teardown.
script=$(basename "${BASH_SOURCE[1]:-$0}" .sh)

log() { echo "$script: $*"; }

fail() {
  echo "$script: $*" >&2
  exit 1
}

# Checks the instance name, $1: it names the server, its buckets and keys.
check_name() {
  [[ $1 =~ ^[a-z][a-z0-9-]{1,30}$ ]] || fail "usage: $script.sh NAME (lowercase letters, digits and dashes)"
}

deploy_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# The instance's directory on this machine, $1 the name: its settings, keys
# included, readable by its owner only.
instance_dir() {
  local dir="$deploy_dir/instances/$1"
  mkdir -p "$dir"
  chmod 700 "$dir"
  echo "$dir"
}

# What a provider's resources are called, kept in file $1 before anything is
# created, so that running again uses the same names and teardown.sh finds
# them. Written once, with the lines given after it and bucket names made
# unique ($2 the instance name): bucket names are shared by everyone on a
# provider. Read back into the environment.
remember() {
  local state=$1 name=$2
  shift 2
  if [ ! -f "$state" ]; then
    local suffix
    suffix=$(openssl rand -hex 4)
    printf '%s\n' "$@" "PHOTOS_BUCKET=$name-$suffix-photos" "BACKUP_BUCKET=$name-$suffix-backup" >"$state"
  fi
  # shellcheck source=/dev/null # written just above, or by an earlier run
  . "$state"
}

# The rules for the backup bucket, as a file: versions Litestream replaces or
# deletes are kept, so no key can erase backup history, for 90 days, as the
# privacy page says; then they go, and delete markers left with nothing
# behind them.
lifecycle_file() {
  local file
  file=$(mktemp)
  cat >"$file" <<'EOF'
{"Rules": [
  {"ID": "old-versions-90-days", "Status": "Enabled", "Filter": {"Prefix": ""},
   "NoncurrentVersionExpiration": {"NoncurrentDays": 90}},
  {"ID": "lone-delete-markers", "Status": "Enabled", "Filter": {"Prefix": ""},
   "Expiration": {"ExpiredObjectDeleteMarker": true}}]}
EOF
  echo "$file"
}

# A value of the instance's jumble.env ($env_file), empty when unset.
setting() {
  [ -f "$env_file" ] && sed -n "s/^$1=//p" "$env_file" | tail -n 1
  return 0
}

# The storage keys, as photos_key, photos_secret, backup_key and
# backup_secret. A key's secret is shown only when it is made: kept in
# jumble.env, and when that has none, the key is made again by command $1,
# given what the key is for (photos or backup) and printing "key secret".
storage_keys() {
  local new_key=$1
  if [ -n "$(setting JUMBLE_S3_SECRET_KEY)" ]; then
    log "photos key kept"
    read -r photos_key photos_secret <<<"$(setting JUMBLE_S3_ACCESS_KEY) $(setting JUMBLE_S3_SECRET_KEY)"
  else
    log "creating the photos key"
    read -r photos_key photos_secret <<<"$("$new_key" photos)"
  fi
  if [ -n "$(setting JUMBLE_BACKUP_SECRET_KEY)" ]; then
    log "backup key kept"
    read -r backup_key backup_secret <<<"$(setting JUMBLE_BACKUP_ACCESS_KEY) $(setting JUMBLE_BACKUP_SECRET_KEY)"
  else
    log "creating the backup key"
    read -r backup_key backup_secret <<<"$("$new_key" backup)"
  fi
  if [ -z "$photos_secret" ] || [ -z "$backup_secret" ]; then
    fail "a storage key came without its secret; run again to make it anew"
  fi
}

# Prepares the server first reached as $2 (user@address) once it answers,
# its host key recorded at first contact, as a new server's cannot be known
# beforehand. deploy.env, in instance directory $1, names it: written the
# first time, then kept as it is, with the admin user bootstrap.sh made and
# whatever else was added.
prepare_server() {
  local dir=$1 host=$2
  if [ -f "$dir/deploy.env" ]; then
    # The user it says, at the server's address now: a server made anew
    # has another.
    local kept
    kept=$(sed -n 's/^HOST=//p' "$dir/deploy.env" | tail -n 1)
    host="${kept%@*}@${host#*@}"
    sed -i.previous "s/^HOST=.*/HOST=$host/" "$dir/deploy.env" && rm -f "$dir/deploy.env.previous"
  else
    cat >"$dir/deploy.env" <<EOF
# Written by deploy/$(basename "$(dirname "${BASH_SOURCE[1]}")")/$script.sh: the server, for deploy.sh.
HOST=$host
# The image built here from this checkout and sent over SSH: no registry.
BUILD=1
EOF
  fi
  log "waiting for SSH on ${host#*@}"
  local tries=60 error
  until error=$(ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new "$host" true 2>&1); do
    tries=$((tries - 1))
    [ "$tries" -gt 0 ] || fail "$host did not answer over SSH for 5 minutes; the last answer:
$error"
    sleep 5
  done
  INSTANCE="$dir" "$deploy_dir/bootstrap.sh"
}

# Writes jumble.env ($env_file) from the example, or as last written, with
# the values known here filled in: JUMBLE_BASE_URL $1, JUMBLE_S3_ENDPOINT $2,
# JUMBLE_S3_REGION $3, the buckets and the keys. Readable by its owner only,
# as it holds the keys.
write_settings() {
  local source_env="$env_file"
  [ -f "$source_env" ] || source_env="$deploy_dir/jumble.env.example"
  (
    umask 077
    # Through the environment, not the command line, where other users of
    # this machine could read the secrets.
    base_url="$1" endpoint="$2" region="$3" photos="$PHOTOS_BUCKET" photos_key="$photos_key" \
    photos_secret="$photos_secret" backup="$BACKUP_BUCKET" backup_key="$backup_key" backup_secret="$backup_secret" \
    awk '
      BEGIN {
        v["JUMBLE_BASE_URL"] = ENVIRON["base_url"]; v["JUMBLE_S3_ENDPOINT"] = ENVIRON["endpoint"]
        v["JUMBLE_S3_REGION"] = ENVIRON["region"]; v["JUMBLE_S3_BUCKET"] = ENVIRON["photos"]
        v["JUMBLE_S3_ACCESS_KEY"] = ENVIRON["photos_key"]; v["JUMBLE_S3_SECRET_KEY"] = ENVIRON["photos_secret"]
        v["JUMBLE_BACKUP_BUCKET"] = ENVIRON["backup"]; v["JUMBLE_BACKUP_ACCESS_KEY"] = ENVIRON["backup_key"]
        v["JUMBLE_BACKUP_SECRET_KEY"] = ENVIRON["backup_secret"]
      }
      { k = $0; sub(/=.*/, "", k) }
      k in v { print k "=" v[k]; next }
      { print }' "$source_env" >"$env_file.next"
  )
  mv "$env_file.next" "$env_file"
}

# The address the instance is served at, given server address $1:
# https://$DOMAIN when given; else the one jumble.env has, kept on a run
# again; else the server's address under sslip.io, which needs no DNS.
base_url() {
  if [ -n "${DOMAIN:-}" ]; then
    echo "https://$DOMAIN"
  elif [ -n "$(setting JUMBLE_BASE_URL)" ] && [[ $(setting JUMBLE_BASE_URL) != *example* ]]; then
    setting JUMBLE_BASE_URL
  else
    echo "https://${1//./-}.sslip.io"
  fi
}

# What to do once provision.sh is done, for instance $1 at address $2, $3
# saying who sends email when the provider does not.
next_steps() {
  cat <<EOF

provision: done. Next:
  1. Fill in $env_file: sign-in, the owner and SMTP
     ($3).
  2. Check the storage keys: deploy/verify-keys.sh $env_file
  3. Deploy: INSTANCE=deploy/instances/$1 deploy/deploy.sh
  Then open $2
EOF
}

# Deletes every object of bucket $1, every version and delete marker
# included, through the script's s3api: the aws CLI's, with the provider's
# endpoint and keys. A thousand at a time, versions and markers together
# (one request each: the CLI's own paging would go past what one delete
# takes); a key that will not go stops it.
empty_bucket() {
  local batch errors
  while true; do
    batch=$(s3api list-object-versions --bucket "$1" --max-keys 1000 --no-paginate --output json |
      python3 -c 'import json, sys
text = sys.stdin.read()
d = json.loads(text) if text.strip() else {}
objects = [{"Key": v["Key"], "VersionId": v["VersionId"]}
           for v in (d.get("Versions") or []) + (d.get("DeleteMarkers") or [])]
print(json.dumps({"Objects": objects, "Quiet": True}) if objects else "")')
    [ -n "$batch" ] || break
    errors=$(s3api delete-objects --bucket "$1" --delete "$batch" --output json |
      python3 -c 'import json, sys
text = sys.stdin.read()
d = json.loads(text) if text.strip() else {}
for e in d.get("Errors") or []: print(e.get("Key"), e.get("Code"))')
    [ -z "$errors" ] || fail "some objects of $1 could not be deleted:
$errors"
  done
}

# Asks for the instance name, $1, again before teardown.sh deletes what $2
# describes; anything else typed stops it.
confirm_teardown() {
  echo "$2"
  echo "It cannot be undone."
  local typed
  read -r -p "Type the name again to go ahead: " typed
  [ "$typed" = "$1" ] || fail "names differ, nothing deleted"
}
