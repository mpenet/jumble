#!/usr/bin/env bash
# Creates a Jumble on AWS: a server (EC2) prepared by bootstrap.sh, a bucket
# for photos and a versioned one for backups, and an IAM user for each with a
# key limited to its bucket, the backup one unable to erase a version.
# Writes deploy/instances/NAME, which deploy.sh deploys from. Run again, it
# creates what is missing and leaves the rest; teardown.sh removes it all.
#
# Experimental: reviewed against the CLIs' documentation, not yet run
# against a real account.
#
#   SSH_KEY=<EC2 key pair name> deploy/aws/provision.sh NAME
#
# The aws CLI's configuration is Jumble's own, apart from any other:
# ~/.config/jumble/aws/config and credentials, made with
# `aws configure` (AWS_CONFIG_FILE and AWS_SHARED_CREDENTIALS_FILE name
# others); its default profile is used, or AWS_PROFILE. Also read: REGION
# (eu-central-1), INSTANCE_TYPE (t3.small), DISK_SIZE (20 GB), ADMIN_CIDR
# (where SSH is open from, everywhere by default) and DOMAIN (the instance's
# host name; by default its address under sslip.io, which needs no DNS and
# gets a certificate all the same).
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
if [ ! -s "$AWS_SHARED_CREDENTIALS_FILE" ] && [ ! -s "$AWS_CONFIG_FILE" ]; then
  fail "no aws configuration in $config_dir; make it, with Jumble's account only:
  AWS_CONFIG_FILE=$AWS_CONFIG_FILE AWS_SHARED_CREDENTIALS_FILE=$AWS_SHARED_CREDENTIALS_FILE aws configure"
fi
: "${SSH_KEY:?the name of an EC2 key pair in the region (aws ec2 describe-key-pairs)}"
instance_type=${INSTANCE_TYPE:-t3.small}
disk_size=${DISK_SIZE:-20}
admin_cidr=${ADMIN_CIDR:-0.0.0.0/0}

dir=$(instance_dir "$name")
env_file="$dir/jumble.env"
# The region is kept with the bucket names.
remember "$dir/aws.env" "$name" "REGION=${REGION:-eu-central-1}"
export AWS_REGION=$REGION
photos=$PHOTOS_BUCKET
backup=$BACKUP_BUCKET

aws ec2 describe-key-pairs --key-names "$SSH_KEY" >/dev/null 2>&1 ||
  fail "no EC2 key pair $SSH_KEY in $REGION"

for bucket in "$photos" "$backup"; do
  if aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    log "bucket $bucket exists"
  else
    log "creating bucket $bucket"
    if [ "$REGION" = us-east-1 ]; then
      aws s3api create-bucket --bucket "$bucket" >/dev/null
    else
      aws s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null
    fi
  fi
  # Private: nothing in it is ever public, photos are shown through links
  # Jumble signs. Set again on every run.
  aws s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
done
aws s3api put-bucket-versioning --bucket "$backup" --versioning-configuration Status=Enabled
log "versioning on for $backup"
# Set again on every run.
lifecycle=$(lifecycle_file)
trap 'rm -f "$lifecycle"' EXIT
aws s3api put-bucket-lifecycle-configuration --bucket "$backup" --lifecycle-configuration "file://$lifecycle"
log "old versions in $backup expire after 90 days"

# Each key may only reach its own bucket. Deleting leaves a delete marker in
# the versioned backup bucket; erasing a version, or changing versioning or
# the lifecycle, would erase backup history: refused outright.
photos_policy=$(cat <<EOF
{"Version": "2012-10-17", "Statement": [
  {"Effect": "Allow",
   "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"],
   "Resource": "arn:aws:s3:::$photos/*"}]}
EOF
)
backup_policy=$(cat <<EOF
{"Version": "2012-10-17", "Statement": [
  {"Effect": "Allow",
   "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload",
              "s3:ListMultipartUploadParts"],
   "Resource": "arn:aws:s3:::$backup/*"},
  {"Effect": "Allow",
   "Action": ["s3:ListBucket", "s3:ListBucketMultipartUploads", "s3:GetBucketLocation"],
   "Resource": "arn:aws:s3:::$backup"},
  {"Effect": "Deny",
   "Action": ["s3:DeleteObjectVersion", "s3:PutBucketVersioning", "s3:PutLifecycleConfiguration",
              "s3:PutBucketPolicy", "s3:DeleteBucket"],
   "Resource": ["arn:aws:s3:::$backup", "arn:aws:s3:::$backup/*"]}]}
EOF
)
for user in "$photos" "$backup"; do
  if aws iam get-user --user-name "$user" >/dev/null 2>&1; then
    log "user $user exists"
  else
    log "creating user $user"
    aws iam create-user --user-name "$user" --tags "Key=jumble,Value=$name" >/dev/null
  fi
  if [ "$user" = "$photos" ]; then policy=$photos_policy; else policy=$backup_policy; fi
  aws iam put-user-policy --user-name "$user" --policy-name "$user" --policy-document "$policy"
done

# A key of the user named as its bucket, the user's earlier ones deleted;
# prints "key secret".
new_key() { # $1 photos or backup
  local user=$photos
  [ "$1" = backup ] && user=$backup
  for old in $(aws iam list-access-keys --user-name "$user" --query 'AccessKeyMetadata[].AccessKeyId' --output text); do
    aws iam delete-access-key --user-name "$user" --access-key-id "$old"
  done
  aws iam create-access-key --user-name "$user" \
    --query 'AccessKey.[AccessKeyId, SecretAccessKey]' --output text
}
storage_keys new_key

vpc=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
[ "$vpc" != None ] || fail "no default VPC in $REGION, which the server goes in"
# Found by the tag this script gives what it creates: nothing else of the
# account's, whatever its name, is taken for Jumble's, nor deleted by
# teardown.sh.
group=$(aws ec2 describe-security-groups --filters "Name=tag:jumble,Values=$name" "Name=vpc-id,Values=$vpc" \
  --query 'SecurityGroups[0].GroupId' --output text)
if [ "$group" != None ]; then
  log "security group $name exists"
else
  log "creating security group $name"
  group=$(aws ec2 create-security-group --group-name "$name" --description "Jumble $name: SSH, HTTP, HTTPS" \
    --vpc-id "$vpc" --tag-specifications "ResourceType=security-group,Tags=[{Key=jumble,Value=$name}]" \
    --query GroupId --output text)
fi
# Each rule added on every run, as a run stopped halfway may have left one
# out; one already there is left as it is.
for rule in "tcp 22 $admin_cidr SSH" "tcp 80 0.0.0.0/0 HTTP" "tcp 443 0.0.0.0/0 HTTPS" "udp 443 0.0.0.0/0 HTTP/3"; do
  read -r protocol port cidr label <<<"$rule"
  if ! out=$(aws ec2 authorize-security-group-ingress --group-id "$group" --ip-permissions \
    "IpProtocol=$protocol,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$cidr,Description=$label}]" 2>&1); then
    [[ $out == *InvalidPermission.Duplicate* ]] || fail "opening $label: $out"
  fi
done

instance=$(aws ec2 describe-instances \
  --filters "Name=tag:jumble,Values=$name" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
if [ "$instance" != None ]; then
  log "instance $name exists"
else
  # Canonical's current Ubuntu 24.04 image, as AWS publishes it.
  image=$(aws ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    --query Parameter.Value --output text)
  log "creating instance $name ($instance_type, $disk_size GB, $REGION)"
  instance=$(aws ec2 run-instances --image-id "$image" --instance-type "$instance_type" --key-name "$SSH_KEY" \
    --security-group-ids "$group" \
    --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=$disk_size,VolumeType=gp3,Encrypted=true}" \
    --metadata-options HttpTokens=required \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name},{Key=jumble,Value=$name}]" \
    --query 'Instances[0].InstanceId' --output text)
fi
aws ec2 wait instance-running --instance-ids "$instance"
ip=$(aws ec2 describe-instances --instance-ids "$instance" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
[ "$ip" != None ] || fail "instance $name has no public address"
url=$(base_url "$ip")
log "instance $name at $ip, reached as $url"

prepare_server "$dir" "ubuntu@$ip"
write_settings "$url" "https://s3.$REGION.amazonaws.com" "$REGION"
next_steps "$name" "$url" "Amazon SES or any SMTP account"
