#!/usr/bin/env bash
# Runs what CI's test job runs, so that a push does not find out there: the
# tests, lint, and the deploy scripts and units, these checked with the tools
# of Ubuntu 24.04 (its shellcheck and Podman), as CI and servers have them.
#
#   dev/check.sh
#
# On Ubuntu 24.04 (CI) these run as they are; elsewhere in a container of
# that system (with Docker or Podman), built once as jumble-check.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

echo "check: tests"
clojure -M:test
echo "check: lint"
clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.10.26"}}}' -M -m clj-kondo.main --lint src test dev

shell_checks='shellcheck deploy/*.sh deploy/*/*.sh dev/*.sh && dev/check-units.sh'
echo "check: deploy scripts and units"
# shellcheck source=/dev/null # the system's own description of itself
if [ "$(. /etc/os-release 2>/dev/null && echo "$ID $VERSION_ID")" = "ubuntu 24.04" ]; then
  bash -c "$shell_checks"
else
  runtime=$(command -v docker || command -v podman) ||
    { echo "check: Docker or Podman needed, for Ubuntu 24.04's shellcheck and Podman" >&2; exit 1; }
  image=jumble-check:ubuntu-24.04
  if ! "$runtime" image inspect "$image" >/dev/null 2>&1; then
    "$runtime" build -q -t "$image" - >/dev/null <<'EOF'
FROM ubuntu:24.04
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends shellcheck podman \
 && rm -rf /var/lib/apt/lists/*
EOF
  fi
  "$runtime" run --rm -v "$root:/src:ro" -w /src "$image" bash -c "$shell_checks"
fi
# Fluent Bit's own check of the log shipping configuration, with the image
# the unit runs.
echo "check: log shipping configuration"
runtime=$(command -v docker || command -v podman) ||
  { echo "check: Docker or Podman needed, for Fluent Bit's configuration check" >&2; exit 1; }
if ! out=$("$runtime" run --rm --platform linux/amd64 -e AXIOM_HOST=us-east-1.aws.edge.axiom.co -e AXIOM_DATASET=check \
  -e AXIOM_TOKEN=check -v "$root/deploy/podman/fluent-bit.yaml:/fluent-bit/etc/fluent-bit.yaml:ro" \
  "$(sed -n 's/^Image=//p' deploy/podman/jumble-logs.container)" -c /fluent-bit/etc/fluent-bit.yaml --dry-run 2>&1); then
  echo "$out" >&2
  exit 1
fi
echo "check: all passed"
