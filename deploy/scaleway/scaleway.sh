# What Scaleway's provision.sh and teardown.sh share, sourced by them: the
# scw configuration that is Jumble's own, and object storage through the aws
# CLI with Scaleway's endpoint and that configuration's key.
# shellcheck shell=bash

export SCW_CONFIG_PATH=${SCW_CONFIG_PATH:-${XDG_CONFIG_HOME:-$HOME/.config}/jumble/scw.yaml}
if [ ! -s "$SCW_CONFIG_PATH" ]; then
  fail "no scw configuration at $SCW_CONFIG_PATH; make it, with Jumble's project only:
  SCW_CONFIG_PATH=$SCW_CONFIG_PATH scw init"
fi
command -v aws >/dev/null || fail "the aws CLI sets up Scaleway's buckets; install it (brew install awscli)"

# The field $1 of a JSON object on standard input.
json_get() {
  python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

# The id of the object named exactly $1 in a JSON list on standard input,
# empty when there is none: scw's lists match names by prefix, and "community"
# must not be taken for "community2".
json_named() {
  python3 -c 'import json, sys
print(next((o["id"] for o in json.load(sys.stdin) or [] if o.get("name") == sys.argv[1]), ""))' "$1"
}

# Sets region, endpoint, project and organization for the zone $ZONE, and
# s3api: the aws CLI's, on Scaleway, with the configuration's key and no
# other aws configuration.
scaleway_storage() {
  region=${ZONE%-*}
  endpoint="https://s3.$region.scw.cloud"
  project=$(scw config get default-project-id)
  organization=$(scw config get default-organization-id)
  if [ -z "$project" ] || [ -z "$organization" ]; then
    fail "$SCW_CONFIG_PATH names no default project and organization"
  fi
}
s3api() {
  AWS_ACCESS_KEY_ID=$(scw config get access-key) AWS_SECRET_ACCESS_KEY=$(scw config get secret-key) \
    AWS_SESSION_TOKEN='' AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_PROFILE='' \
    aws --endpoint-url "$endpoint" --region "$region" s3api "$@"
}
