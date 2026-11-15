#!/usr/bin/env bash
# Removes a Jumble provision.sh created on Scaleway, for good: the instance
# with its volumes and address, its security group, both buckets with every
# object version (photos and backups), and the applications with their keys
# and policies, as named in deploy/instances/NAME/scaleway.env. Asks for the
# name again before deleting anything. deploy/instances/NAME is left, its
# keys then void.
#
# Experimental: reviewed against the CLIs' documentation, not yet run
# against a real account.
#
#   deploy/scaleway/teardown.sh NAME
#
# With the scw configuration provision.sh uses: SCW_CONFIG_PATH,
# ~/.config/jumble/scw.yaml by default.
set -euo pipefail
# shellcheck source=deploy/lib.sh
. "$(dirname "$0")/../lib.sh"
# shellcheck source=deploy/scaleway/scaleway.sh
. "$(dirname "$0")/scaleway.sh"

name=${1:-}
check_name "$name"
log "experimental on Scaleway: not yet run against a real account; check its console if a step fails"
state="$deploy_dir/instances/$name/scaleway.env"
[ -f "$state" ] || fail "no $state, which names what provision.sh created; nothing deleted"
# shellcheck source=/dev/null # written by provision.sh
. "$state"
scaleway_storage
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET

confirm_teardown "$name" "This deletes, with $SCW_CONFIG_PATH, in $ZONE:
instance $name with its volumes and address, security group $name, buckets
$photos and $backup with all their versions, and the applications, keys
and policies of the same names."

server=$(scw instance server list name="$name" zone="$ZONE" -o json | json_named "$name")
if [ -n "$server" ]; then
  log "deleting instance $name, its volumes and address"
  scw instance server terminate "$server" zone="$ZONE" with-ip=true with-block=true --wait >/dev/null
fi
group=$(scw instance security-group list name="$name" zone="$ZONE" -o json | json_named "$name")
if [ -n "$group" ]; then
  log "deleting security group $name"
  # The terminated server may hold on to it a moment.
  for tries in $(seq 12 -1 1); do
    scw instance security-group delete "$group" zone="$ZONE" >/dev/null 2>&1 && break
    [ "$tries" -gt 1 ] || fail "security group $name is still in use; delete it once the instance is gone"
    sleep 10
  done
fi

for bucket in "$photos" "$backup"; do
  if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    log "emptying and deleting bucket $bucket, versions included"
    empty_bucket "$bucket"
    s3api delete-bucket --bucket "$bucket"
  fi
done

for app in "$photos" "$backup"; do
  id=$(scw iam application list name="$app" organization-id="$organization" -o json | json_named "$app")
  if [ -n "$id" ]; then
    for policy in $(scw iam policy list application-ids.0="$id" organization-id="$organization" -o json |
      python3 -c 'import json, sys
for p in json.load(sys.stdin) or []: print(p["id"])'); do
      scw iam policy delete "$policy" >/dev/null
    done
    # Its keys go with it.
    scw iam application delete "$id" >/dev/null
    log "deleted application $app"
  fi
done
log "done"
