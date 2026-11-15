#!/usr/bin/env bash
# Removes a Jumble provision.sh created on AWS, for good: the instance and its
# disk, its security group, both buckets with every object version (photos
# and backups), and the IAM users with their keys, as named in
# deploy/instances/NAME/aws.env. Asks for the name again before deleting
# anything. deploy/instances/NAME is left, its keys then void.
#
# Experimental: reviewed against the CLIs' documentation, not yet run
# against a real account.
#
#   deploy/aws/teardown.sh NAME
#
# With the aws configuration provision.sh uses: ~/.config/jumble/aws by
# default.
set -euo pipefail
# shellcheck source=deploy/lib.sh
. "$(dirname "$0")/../lib.sh"

name=${1:-}
check_name "$name"
log "experimental on AWS: not yet run against a real account; check its console if a step fails"
config_dir=${XDG_CONFIG_HOME:-$HOME/.config}/jumble/aws
export AWS_CONFIG_FILE=${AWS_CONFIG_FILE:-$config_dir/config}
export AWS_SHARED_CREDENTIALS_FILE=${AWS_SHARED_CREDENTIALS_FILE:-$config_dir/credentials}
# Keys or a region already in the environment would win over that
# configuration, and act on whatever account they are for.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_REGION AWS_DEFAULT_REGION
state="$deploy_dir/instances/$name/aws.env"
[ -f "$state" ] || fail "no $state, which names what provision.sh created; nothing deleted"
# shellcheck source=/dev/null # written by provision.sh
. "$state"
export AWS_REGION=$REGION
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET
s3api() { aws s3api "$@"; }

confirm_teardown "$name" "This deletes, with $AWS_SHARED_CREDENTIALS_FILE${AWS_PROFILE:+ (profile $AWS_PROFILE)}, in $REGION:
instance $name with its disk, security group $name, buckets $photos and
$backup with all their versions, and the users and keys of the same names."

# Only what provision.sh created, by the tag it gave them.
instances=$(aws ec2 describe-instances \
  --filters "Name=tag:jumble,Values=$name" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [ -n "$instances" ]; then
  log "terminating instance $name"
  # shellcheck disable=SC2086 # one or more ids
  aws ec2 terminate-instances --instance-ids $instances >/dev/null
  # shellcheck disable=SC2086
  aws ec2 wait instance-terminated --instance-ids $instances
fi
group=$(aws ec2 describe-security-groups --filters "Name=tag:jumble,Values=$name" \
  --query 'SecurityGroups[0].GroupId' --output text)
if [ "$group" != None ]; then
  log "deleting security group $name"
  # The terminated instance's network interface may linger a moment.
  for tries in $(seq 12 -1 1); do
    aws ec2 delete-security-group --group-id "$group" 2>/dev/null && break
    [ "$tries" -gt 1 ] || fail "security group $name is still in use; delete it once the instance is gone"
    sleep 10
  done
fi

for bucket in "$photos" "$backup"; do
  if aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    log "emptying and deleting bucket $bucket, versions included"
    empty_bucket "$bucket"
    aws s3api delete-bucket --bucket "$bucket"
  fi
done

for user in "$photos" "$backup"; do
  if aws iam get-user --user-name "$user" >/dev/null 2>&1; then
    for key in $(aws iam list-access-keys --user-name "$user" --query 'AccessKeyMetadata[].AccessKeyId' --output text); do
      aws iam delete-access-key --user-name "$user" --access-key-id "$key"
    done
    aws iam delete-user-policy --user-name "$user" --policy-name "$user" 2>/dev/null || true
    aws iam delete-user --user-name "$user"
    log "deleted user $user and its keys"
  fi
done
log "done"
