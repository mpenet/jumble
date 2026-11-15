#!/usr/bin/env bash
# Checks deploy/podman's unit, in each mode, with Podman's own Quadlet
# generator: a key this Podman does not know, or a unit it cannot turn into
# a service, fails. Needs Podman with Quadlet (4.4 or later), as on servers.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
generator=/usr/lib/systemd/system-generators/podman-system-generator
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

for mode in https own-proxy; do
  "$here/deploy/podman/unit.sh" "$mode" "localhost/jumble:check" >"$dir/jumble.container"
  cp "$here/deploy/podman/jumble-data.volume" "$dir/"
  if ! out=$(QUADLET_UNIT_DIRS="$dir" "$generator" --dryrun 2>&1) || ! grep -q -- '---jumble.service---' <<<"$out"; then
    echo "check-units: the $mode unit does not convert:" >&2
    echo "$out" | grep -v '^#' >&2
    exit 1
  fi
  echo "check-units: $mode unit converts ($(podman --version))"
done
