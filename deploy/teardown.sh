#!/usr/bin/env bash
# Removes, for good, a Jumble provision.sh created on a cloud provider, by
# its own teardown.sh, asking for the name again first.
#
#   deploy/teardown.sh PROVIDER NAME
#
# PROVIDER is one of the directories next to this script holding a
# teardown.sh: exoscale, aws or scaleway. See each for what it reads.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
provider=${1:-}
if [ -z "$provider" ] || [ ! -x "$here/$provider/teardown.sh" ]; then
  providers=$(for script in "$here"/*/teardown.sh; do basename "$(dirname "$script")"; done | paste -sd'|' -)
  echo "usage: teardown.sh $providers NAME" >&2
  exit 1
fi
shift
exec "$here/$provider/teardown.sh" "$@"
