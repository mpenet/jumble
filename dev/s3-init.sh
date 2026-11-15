#!/bin/sh
# One-time setup of the local Garage started by docker compose: gives the
# single node storage, then creates the dev key and the buckets for photos
# and database backups.
set -eu
garage() { docker compose exec -T garage /garage "$@"; }

# Garage answers a few seconds after its container starts.
tries=0
until garage status >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 30 ]; then
    echo "Garage did not answer, is docker compose up?" >&2
    exit 1
  fi
  sleep 1
done

node=$(garage status 2>/dev/null | awk '/NO ROLE ASSIGNED/ {print $1; exit}')
if [ -n "$node" ]; then
  garage layout assign -z dev -c 1G "$node"
  garage layout apply --version 1
fi
garage key import --yes -n jumble-dev GK0123456789abcdef01234567 \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef 2>/dev/null || true
garage bucket create jumble 2>/dev/null || true
garage bucket allow --read --write --owner jumble --key jumble-dev
garage bucket create jumble-backup 2>/dev/null || true
garage bucket allow --read --write --owner jumble-backup --key jumble-dev
