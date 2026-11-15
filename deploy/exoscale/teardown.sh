#!/usr/bin/env bash
# Removes a Jumble provision.sh created on Exoscale, for good: the instance
# and its disk, its security group, both buckets with every object version
# (photos and backups), the storage keys and their roles, as named in
# deploy/instances/NAME/exoscale.env. Asks for the name again before
# deleting anything. deploy/instances/NAME is left, its keys then void.
#
#   deploy/exoscale/teardown.sh NAME
#
# With the exo configuration provision.sh uses: EXOSCALE_CONFIG,
# ~/.config/jumble/exoscale.toml by default.
set -euo pipefail
# shellcheck source=deploy/lib.sh
. "$(dirname "$0")/../lib.sh"

name=${1:-}
check_name "$name"
export EXOSCALE_CONFIG=${EXOSCALE_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/jumble/exoscale.toml}
[ -s "$EXOSCALE_CONFIG" ] || fail "no exo configuration at $EXOSCALE_CONFIG"
state="$deploy_dir/instances/$name/exoscale.env"
[ -f "$state" ] || fail "no $state, which names what provision.sh created; nothing deleted"
# shellcheck source=/dev/null # written by provision.sh
. "$state"
# shellcheck disable=SC2153 # ZONE comes from exoscale.env
zone=$ZONE
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET

confirm_teardown "$name" "This deletes, with $EXOSCALE_CONFIG${EXOSCALE_ACCOUNT:+ (account $EXOSCALE_ACCOUNT)}, in $zone:
instance $name with its data, security group $name, buckets $photos and
$backup with all their versions, and the keys and roles of the same names."

if exo compute instance show --zone "$zone" "$name" >/dev/null 2>&1; then
  log "deleting instance $name"
  exo compute instance delete --force --zone "$zone" "$name" >/dev/null
fi
if exo compute security-group show "$name" >/dev/null 2>&1; then
  log "deleting security group $name"
  exo compute security-group delete --force "$name" >/dev/null
fi
for bucket in "$photos" "$backup"; do
  if exo storage show "sos://$bucket" >/dev/null 2>&1; then
    log "emptying and deleting bucket $bucket, versions included"
    exo storage purge --force "sos://$bucket/" >/dev/null
    exo storage rb --force --recursive "sos://$bucket" >/dev/null
  fi
done
for key in "$photos" "$backup"; do
  if exo iam api-key delete --force "$key" >/dev/null 2>&1; then log "deleted key $key"; fi
  if exo iam role delete --force "$key" >/dev/null 2>&1; then log "deleted role $key"; fi
done
log "done"
