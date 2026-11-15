#!/usr/bin/env bash
# Creates a Jumble on a cloud provider, by its own provision.sh: a server
# prepared by bootstrap.sh, the photos and backup buckets and their keys.
#
#   deploy/provision.sh PROVIDER NAME
#
# PROVIDER is one of the directories next to this script holding a
# provision.sh: exoscale, aws or scaleway. See each for what it reads.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
provider=${1:-}
if [ -z "$provider" ] || [ ! -x "$here/$provider/provision.sh" ]; then
  providers=$(for script in "$here"/*/provision.sh; do basename "$(dirname "$script")"; done | paste -sd'|' -)
  echo "usage: provision.sh $providers NAME" >&2
  exit 1
fi
shift
exec "$here/$provider/provision.sh" "$@"
