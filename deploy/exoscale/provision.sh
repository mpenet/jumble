#!/usr/bin/env bash
# Creates a Jumble on Exoscale, to try it: a server running Podman, a bucket
# for photos and a versioned one for backups, and a storage key for each,
# limited to its bucket, the backup one unable to erase a version. Writes
# deploy/instances/NAME, which deploy.sh deploys from. Run again, it creates
# what is missing and leaves the rest; teardown.sh removes it all.
#
#   SSH_KEY=<exo SSH key name> deploy/exoscale/provision.sh NAME
#
# The exo CLI's configuration is Jumble's own, apart from any other:
# EXOSCALE_CONFIG, ~/.config/jumble/exoscale.toml by default, made with
# `exo config add`; its default account is used, or EXOSCALE_ACCOUNT. Also
# read: ZONE (ch-gva-2), INSTANCE_TYPE (standard.small), DISK_SIZE (20 GB),
# ADMIN_CIDR (where SSH is open from, everywhere by default) and DOMAIN (the
# instance's host name; by default its address under sslip.io, which needs
# no DNS and gets a certificate all the same).
set -euo pipefail
# shellcheck source=deploy/lib.sh
. "$(dirname "$0")/../lib.sh"

name=${1:-}
check_name "$name"
export EXOSCALE_CONFIG=${EXOSCALE_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/jumble/exoscale.toml}
if [ ! -s "$EXOSCALE_CONFIG" ]; then
  echo "provision: no exo configuration at $EXOSCALE_CONFIG; make it, with Jumble's account only:" >&2
  echo "  mkdir -p \"$(dirname "$EXOSCALE_CONFIG")\" && install -m 600 /dev/null \"$EXOSCALE_CONFIG\"" >&2
  echo "  EXOSCALE_CONFIG=\"$EXOSCALE_CONFIG\" exo config add" >&2
  exit 1
fi
: "${SSH_KEY:?the name of an SSH key registered with Exoscale (exo compute ssh-key list)}"
# Old backup versions must expire (see below): checked before anything is made.
# An exo without the command shows its parent's help, and succeeds, instead.
if ! exo storage bucket --help 2>&1 | grep -q -E '^\s+lifecycle\s'; then
  echo "provision: this exo cannot set bucket lifecycles, which backups need; upgrade it (1.102 can)" >&2
  exit 1
fi
zone=${ZONE:-ch-gva-2}
instance_type=${INSTANCE_TYPE:-standard.small}
disk_size=${DISK_SIZE:-20}
admin_cidr=${ADMIN_CIDR:-0.0.0.0/0}
dir=$(instance_dir "$name")
env_file="$dir/jumble.env"

# The zone is kept with the bucket names.
remember "$dir/exoscale.env" "$name" "ZONE=$zone"
zone=$ZONE
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET

# Each key may only reach its own bucket; whatever no rule allows is refused.
photos_policy=$(cat <<EOF
{"default-service-strategy": "deny",
 "services": {"sos": {"type": "rules", "rules": [
   {"expression": "parameters.bucket != '$photos'", "action": "deny"},
   {"expression": "operation in ['put-object', 'get-object', 'head-object', 'delete-object', 'abort-multipart-upload']",
    "action": "allow"}]}}}
EOF
)
# Deleting leaves a delete marker in a versioned bucket; deleting a version
# would erase backup history, so the key may not, nor change versioning.
backup_policy=$(cat <<EOF
{"default-service-strategy": "deny",
 "services": {"sos": {"type": "rules", "rules": [
   {"expression": "parameters.bucket != '$backup'", "action": "deny"},
   {"expression": "operation == 'delete-object' && parameters.version_id != ''", "action": "deny"},
   {"expression": "operation in ['put-object', 'get-object', 'head-object', 'delete-object', 'list-objects', 'abort-multipart-upload', 'list-multipart-uploads', 'head-bucket', 'get-bucket-location']",
    "action": "allow"}]}}}
EOF
)

for bucket in "$photos" "$backup"; do
  if exo storage show "sos://$bucket" >/dev/null 2>&1; then
    log "bucket $bucket exists"
  else
    log "creating bucket $bucket"
    exo storage mb --zone "$zone" "sos://$bucket"
  fi
done
exo storage bucket versioning enable "sos://$backup" >/dev/null
log "versioning on for $backup"
# Set again on every run.
lifecycle=$(lifecycle_file)
trap 'rm -f "$lifecycle"' EXIT
exo storage bucket lifecycle set --zone "$zone" "sos://$backup" "$lifecycle" >/dev/null
log "old versions in $backup expire after 90 days"

for role in "$photos" "$backup"; do
  if exo iam role show "$role" >/dev/null 2>&1; then
    log "role $role exists"
  else
    log "creating role $role"
    if [ "$role" = "$photos" ]; then policy=$photos_policy; else policy=$backup_policy; fi
    echo "$policy" | exo iam role create "$role" --description "Jumble $name: $role bucket only" --policy - >/dev/null
  fi
done

# A key, with the role of the same name as its bucket; prints "key secret".
new_key() { # $1 photos or backup
  local role=$photos
  [ "$1" = backup ] && role=$backup
  exo iam api-key delete --force "$role" >/dev/null 2>&1 || true
  exo iam api-key create "$role" "$role" --output-format json |
    python3 -c 'import json, sys; k = json.load(sys.stdin); print(k["key"], k["secret"])'
}
storage_keys new_key

if exo compute security-group show "$name" >/dev/null 2>&1; then
  log "security group $name exists"
else
  log "creating security group $name"
  exo compute security-group create "$name" --description "Jumble $name: SSH, HTTP, HTTPS" >/dev/null
  exo compute security-group rule add "$name" --port 22 --network "$admin_cidr" --description SSH >/dev/null
  exo compute security-group rule add "$name" --port 80 --network 0.0.0.0/0 --description HTTP >/dev/null
  exo compute security-group rule add "$name" --port 443 --network 0.0.0.0/0 --description HTTPS >/dev/null
  exo compute security-group rule add "$name" --port 443 --protocol udp --network 0.0.0.0/0 --description HTTP/3 >/dev/null
fi

if exo compute instance show --zone "$zone" "$name" >/dev/null 2>&1; then
  log "instance $name exists"
else
  log "creating instance $name ($instance_type, $disk_size GB, $zone)"
  exo compute instance create "$name" --zone "$zone" --instance-type "$instance_type" --disk-size "$disk_size" \
    --template "Linux Ubuntu 24.04 LTS 64-bit" --security-group "$name" --ssh-key "$SSH_KEY" >/dev/null
fi
# The first IPv4 address of the instance's description.
ip=$(exo compute instance show --zone "$zone" "$name" --output-format json |
  python3 -c 'import json, re, sys
def ips(v):
    if isinstance(v, dict): v = list(v.values())
    if isinstance(v, list):
        for x in v: yield from ips(x)
    elif isinstance(v, str) and re.fullmatch(r"\d+\.\d+\.\d+\.\d+", v): yield v
print(next(ips(json.load(sys.stdin)), ""))')
if [ -z "$ip" ]; then
  fail "no address found for instance $name"
fi
url=$(base_url "$ip")
log "instance $name at $ip, reached as $url"

prepare_server "$dir" "ubuntu@$ip"
write_settings "$url" "https://sos-$zone.exo.io" "$zone"
next_steps "$name" "$url" "Exoscale sends no email; any SMTP account works"
