#!/usr/bin/env bash
# Creates a Jumble on Scaleway: a server (Instance) prepared by bootstrap.sh,
# a bucket for photos and a versioned one for backups, and an IAM
# application for each with a key limited to its bucket by a bucket policy,
# the backup one unable to erase a version. Writes deploy/instances/NAME,
# which deploy.sh deploys from. Run again, it creates what is missing and
# leaves the rest; teardown.sh removes it all.
#
# Experimental: reviewed against the CLIs' documentation, not yet run
# against a real account.
#
#   deploy/scaleway/provision.sh NAME
#
# The scw CLI's configuration is Jumble's own, apart from any other:
# SCW_CONFIG_PATH, ~/.config/jumble/scw.yaml by default, made with
# `scw init`; its default project gets everything, and the SSH keys of that
# project reach the server. Buckets are set up with the aws CLI, given
# Scaleway's endpoint and that configuration's key. Also read: ZONE
# (fr-par-1), INSTANCE_TYPE (DEV1-S), ADMIN_CIDR (where SSH is open from,
# everywhere by default) and DOMAIN (the instance's host name; by default
# its address under sslip.io, which needs no DNS and gets a certificate all
# the same).
set -euo pipefail
# shellcheck source=deploy/lib.sh
. "$(dirname "$0")/../lib.sh"
# shellcheck source=deploy/scaleway/scaleway.sh
. "$(dirname "$0")/scaleway.sh"

name=${1:-}
check_name "$name"
log "experimental on Scaleway: not yet run against a real account; check its console if a step fails"
instance_type=${INSTANCE_TYPE:-DEV1-S}
admin_cidr=${ADMIN_CIDR:-0.0.0.0/0}

dir=$(instance_dir "$name")
env_file="$dir/jumble.env"
# The zone is kept with the bucket names.
remember "$dir/scaleway.env" "$name" "ZONE=${ZONE:-fr-par-1}"
scaleway_storage
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET

for bucket in "$photos" "$backup"; do
  if s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    log "bucket $bucket exists"
  else
    log "creating bucket $bucket"
    s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$region" >/dev/null
  fi
done
s3api put-bucket-versioning --bucket "$backup" --versioning-configuration Status=Enabled
log "versioning on for $backup"
# Set again on every run.
lifecycle=$(lifecycle_file)
trap 'rm -f "$lifecycle"' EXIT
s3api put-bucket-lifecycle-configuration --bucket "$backup" --lifecycle-configuration "file://$lifecycle"
log "old versions in $backup expire after 90 days"

# An application per bucket, allowed object storage in the project; each
# bucket's policy then lets in only its own application, for what Jumble
# needs, and whoever runs this script, to manage it. Deleting leaves a
# delete marker in the versioned backup bucket; erasing a version is not
# among what its application may do.
application() { # $1 the name; prints its id, creating it and its policy if missing
  local id
  id=$(scw iam application list name="$1" organization-id="$organization" -o json | json_named "$1")
  if [ -z "$id" ]; then
    id=$(scw iam application create name="$1" organization-id="$organization" \
      description="Jumble $name: $1 bucket only" -o json | json_get id)
  fi
  # Checked on its own: a run stopped halfway may have made the application
  # and not its policy.
  if [ -z "$(scw iam policy list application-ids.0="$id" organization-id="$organization" -o json |
    python3 -c 'import json, sys; print(len(json.load(sys.stdin) or []) or "")')" ]; then
    scw iam policy create name="$1" application-id="$id" organization-id="$organization" \
      rules.0.permission-set-names.0=ObjectStorageFullAccess rules.0.project-ids.0="$project" >/dev/null
  fi
  echo "$id"
}
log "applications $photos and $backup"
photos_app=$(application "$photos")
backup_app=$(application "$backup")
me=$(scw iam api-key get "$(scw config get access-key)" with-policies=false -o json |
  python3 -c 'import json, sys; k = json.load(sys.stdin)["APIKey"]
print("application_id:" + k["application_id"] if k.get("application_id") else "user_id:" + k["user_id"])')

bucket_policy() { # $1 bucket, $2 its application, $3 actions on objects, $4 actions on the bucket
  cat <<EOF
{"Version": "2023-04-17", "Statement": [
  {"Sid": "Jumble", "Effect": "Allow", "Principal": {"SCW": "application_id:$2"},
   "Action": [$3], "Resource": ["$1/*"]},
  $([ -n "$4" ] && echo "{\"Sid\": \"JumbleBucket\", \"Effect\": \"Allow\", \"Principal\": {\"SCW\": \"application_id:$2\"}, \"Action\": [$4], \"Resource\": [\"$1\"]},")
  {"Sid": "Manage", "Effect": "Allow", "Principal": {"SCW": "$me"},
   "Action": ["s3:*"], "Resource": ["$1", "$1/*"]}]}
EOF
}
s3api put-bucket-policy --bucket "$photos" --policy "$(bucket_policy "$photos" "$photos_app" \
  '"s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"' '')"
s3api put-bucket-policy --bucket "$backup" --policy "$(bucket_policy "$backup" "$backup_app" \
  '"s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"' \
  '"s3:ListBucket", "s3:ListBucketMultipartUploads", "s3:GetBucketLocation"')"
log "each bucket's policy lets in its own application only"

# A key of the application named as its bucket, its earlier ones deleted;
# prints "key secret".
new_key() { # $1 photos or backup
  local app=$photos_app old
  [ "$1" = backup ] && app=$backup_app
  for old in $(scw iam api-key list application-id="$app" -o json | python3 -c 'import json, sys
for k in json.load(sys.stdin) or []: print(k["access_key"])'); do
    scw iam api-key delete "$old" >/dev/null
  done
  scw iam api-key create application-id="$app" default-project-id="$project" description="Jumble $name" -o json |
    python3 -c 'import json, sys; k = json.load(sys.stdin); print(k["access_key"], k["secret_key"])'
}
storage_keys new_key

group=$(scw instance security-group list name="$name" zone="$ZONE" -o json | json_named "$name")
if [ -n "$group" ]; then
  log "security group $name exists"
else
  log "creating security group $name"
  # Stateful, letting out what goes out, refusing what comes in but the
  # rules below: the defaults, named differently from one scw version to
  # the next, so not named here. Scaleway refuses mail out on ports 25, 465
  # and 587 until the account may send it; its Transactional Email also
  # listens on 2465.
  group=$(scw instance security-group create name="$name" zone="$ZONE" description="Jumble $name: SSH, HTTP, HTTPS" -o json |
    python3 -c 'import json, sys; d = json.load(sys.stdin); print((d.get("security_group") or d)["id"])')
  for rule in "TCP 22 $admin_cidr" "TCP 80 0.0.0.0/0" "TCP 443 0.0.0.0/0" "UDP 443 0.0.0.0/0"; do
    read -r protocol port cidr <<<"$rule"
    scw instance security-group create-rule security-group-id="$group" zone="$ZONE" direction=inbound action=accept \
      protocol="$protocol" dest-port-from="$port" ip-range="$cidr" >/dev/null
  done
fi

server=$(scw instance server list name="$name" zone="$ZONE" -o json | json_named "$name")
if [ -n "$server" ]; then
  log "instance $name exists"
else
  log "creating instance $name ($instance_type, $ZONE)"
  server=$(scw instance server create name="$name" type="$instance_type" image=ubuntu_noble zone="$ZONE" \
    security-group-id="$group" ip=new tags.0="jumble=$name" --wait -o json | json_get id)
fi
ip=$(scw instance server get "$server" zone="$ZONE" -o json | python3 -c 'import json, sys
s = json.load(sys.stdin)
ips = [i.get("address") for i in s.get("public_ips") or []] + [(s.get("public_ip") or {}).get("address")]
print(next((i for i in ips if i and ":" not in i), ""))')
[ -n "$ip" ] || fail "instance $name has no public IPv4 address"
url=$(base_url "$ip")
log "instance $name at $ip, reached as $url"

# Scaleway's Ubuntu lets root in, with the project's keys: bootstrap.sh
# makes the admin user deploy.sh connects as.
prepare_server "$dir" "root@$ip"
write_settings "$url" "$endpoint" "$region"
next_steps "$name" "$url" "Scaleway Transactional Email on port 2465: Scaleway refuses mail out on 25, 465 and 587 at first"
