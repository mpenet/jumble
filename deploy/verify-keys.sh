#!/usr/bin/env bash
# Checks that the photo and backup storage keys can do what Jumble needs and
# no more: each works on its own bucket only, and the backup key cannot erase
# an object version (only hide it behind a delete marker), so backup history
# survives a leaked key. Needs the backup bucket versioned. Leaves one small
# object version in the backup bucket, the proof that it survived.
#
#   deploy/verify-keys.sh deploy/instances/community/jumble.env
#
# The file given is read for the keys (JUMBLE_S3_ACCESS_KEY,
# JUMBLE_S3_SECRET_KEY, JUMBLE_BACKUP_ACCESS_KEY, JUMBLE_BACKUP_SECRET_KEY)
# and, unless given as ENDPOINT, REGION, PHOTOS_BUCKET and BACKUP_BUCKET,
# for where they work (JUMBLE_S3_ENDPOINT, JUMBLE_S3_REGION,
# JUMBLE_S3_BUCKET, JUMBLE_BACKUP_BUCKET).
set -euo pipefail

env_file=${1:?a file with the keys, such as jumble.env}

# A variable's value in the env file, read as Podman reads it: as it is, up
# to the end of the line; not sourced, as other values (the mail sender's
# "Name <address>") are not shell syntax.
setting() {
  sed -n "s/^$1=//p" "$env_file" | tail -n 1
}

ENDPOINT=${ENDPOINT:-$(setting JUMBLE_S3_ENDPOINT)}
REGION=${REGION:-$(setting JUMBLE_S3_REGION)}
PHOTOS_BUCKET=${PHOTOS_BUCKET:-$(setting JUMBLE_S3_BUCKET)}
BACKUP_BUCKET=${BACKUP_BUCKET:-$(setting JUMBLE_BACKUP_BUCKET)}
: "${ENDPOINT:?the storage endpoint, e.g. https://s3.example.com}"
: "${REGION:?the storage region}"
: "${PHOTOS_BUCKET:?}"
: "${BACKUP_BUCKET:?}"

failures=0
probe="verify/$(date +%s)"

# curl signing with key $1 and secret $2, given on its standard input rather
# than its command line, where other users of the machine could see them.
signed() {
  local key=$1 secret=$2
  shift 2
  printf 'user = "%s:%s"\n' "$key" "$secret" | curl -sS -K - --aws-sigv4 "aws:amz:$REGION:s3" "$@"
}

# HTTP status of an S3 request signed with key $1 and secret $2.
s3() {
  local key=$1 secret=$2
  shift 2
  signed "$key" "$secret" -o /dev/null -w '%{http_code}' "$@"
}

expect() { # $1 description, $2 expected status pattern, rest: s3 arguments
  local what=$1 want=$2 got
  shift 2
  got=$(s3 "$@")
  if [[ $got =~ ^($want)$ ]]; then
    echo "ok   $what ($got)"
  else
    echo "FAIL $what: expected $want, got $got"
    failures=$((failures + 1))
  fi
}

photos=("$(setting JUMBLE_S3_ACCESS_KEY)" "$(setting JUMBLE_S3_SECRET_KEY)")
backup=("$(setting JUMBLE_BACKUP_ACCESS_KEY)" "$(setting JUMBLE_BACKUP_SECRET_KEY)")

expect "photos key writes photos" 200 "${photos[@]}" -X PUT --data-binary probe "$ENDPOINT/$PHOTOS_BUCKET/$probe"
expect "photos key reads photos" 200 "${photos[@]}" "$ENDPOINT/$PHOTOS_BUCKET/$probe"
expect "photos key deletes photos" 204 "${photos[@]}" -X DELETE "$ENDPOINT/$PHOTOS_BUCKET/$probe"
expect "photos key cannot list photos" 403 "${photos[@]}" "$ENDPOINT/$PHOTOS_BUCKET/"
expect "photos key cannot read backups" 403 "${photos[@]}" "$ENDPOINT/$BACKUP_BUCKET/$probe"
expect "photos key cannot write backups" 403 "${photos[@]}" -X PUT --data-binary probe "$ENDPOINT/$BACKUP_BUCKET/$probe"

# The version written, as the response to the upload reports it.
version=$(signed "${backup[0]}" "${backup[1]}" -o /dev/null -D - -X PUT --data-binary probe \
  "$ENDPOINT/$BACKUP_BUCKET/$probe" |
  tr -d '\r' | awk 'tolower($1) == "x-amz-version-id:" {print $2}')
if [[ -n $version ]]; then
  echo "ok   backup key writes backups, as versions ($version)"
else
  echo "FAIL backup key writes backups, as versions: no version id (is versioning on, and the write allowed?)"
  failures=$((failures + 1))
fi
expect "backup key lists backups" 200 "${backup[@]}" "$ENDPOINT/$BACKUP_BUCKET/?list-type=2&prefix=verify/"
expect "backup key cannot read photos" 403 "${backup[@]}" "$ENDPOINT/$PHOTOS_BUCKET/$probe"
expect "backup key cannot write photos" 403 "${backup[@]}" -X PUT --data-binary probe "$ENDPOINT/$PHOTOS_BUCKET/$probe"
# Asking to keep versioning on needs the same right as turning it off, and
# changes nothing if the key wrongly has it.
expect "backup key cannot change versioning" 403 "${backup[@]}" -X PUT \
  --data-binary '<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>' \
  "$ENDPOINT/$BACKUP_BUCKET/?versioning"

expect "backup key deletes, leaving a marker" 204 "${backup[@]}" -X DELETE "$ENDPOINT/$BACKUP_BUCKET/$probe"
if [[ -n $version ]]; then
  expect "backup key cannot erase a version" 403 "${backup[@]}" -X DELETE \
    "$ENDPOINT/$BACKUP_BUCKET/$probe?versionId=$version"
fi

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
