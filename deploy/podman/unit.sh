#!/usr/bin/env bash
# Prints the jumble.container unit deploy.sh installs: the container's own
# settings, those of MODE (https or own-proxy), and IMAGE, in one file, as
# Podman 4's Quadlet reads no drop-in directory.
#
#   deploy/podman/unit.sh https localhost/jumble:<digest>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
mode=${1:?MODE: https or own-proxy}
image=${2:?IMAGE}
if [ ! -f "$here/$mode.conf" ]; then
  echo "unit.sh: MODE must be https or own-proxy" >&2
  exit 1
fi
cat "$here/jumble.container"
echo
cat "$here/$mode.conf"
printf '\n[Container]\nImage=%s\n' "$image"
