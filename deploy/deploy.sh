#!/usr/bin/env bash
# Runs image $IMAGE (by digest) on a server as the systemd service jumble,
# through Podman's Quadlet (deploy/podman/), then waits until it answers its
# health check. The previous image is printed, to roll back to. The server
# needs Podman, and the user sudo without a password prompt: bootstrap.sh
# prepares one (see doc/deployment.md).
#
# MODE is https (default: Jumble serves HTTPS itself) or own-proxy (plain
# HTTP on the loopback, for a proxy of your own).
#
# With INSTANCE, a directory of this machine holding the instance's
# configuration, the server's settings come from there: deploy.env names
# the server (HOST) and optionally MODE, and jumble.env is installed as
# /etc/jumble/jumble.env. Without it, HOST is given and the server's own
# /etc/jumble/jumble.env is used.
#
# With SHIP=1, the server needs no access to the registry: the image is
# pulled here, sent over SSH and checked to be the same there, layer by
# layer, then run from that copy.
#
# IMAGE may name a tag instead (ghcr.io/<owner>/jumble:main, the latest
# image CI tested and pushed): it is pulled here and resolved to its
# digest, which is what runs.
#
# With BUILD=1, no registry at all: the image is built here, from this
# checkout, for the server's platform (linux/amd64), labelled with its
# commit (-modified when files it is built from have uncommitted changes),
# and shipped as with SHIP=1; IMAGE is not used.
#
# deploy.env may set IMAGE, SHIP, BUILD and MODE too; the command line wins.
#
#   IMAGE=ghcr.io/<owner>/jumble@sha256:... INSTANCE=deploy/instances/community deploy/deploy.sh
#   IMAGE=ghcr.io/<owner>/jumble:main SHIP=1 INSTANCE=deploy/instances/community deploy/deploy.sh
#   BUILD=1 INSTANCE=deploy/instances/community deploy/deploy.sh
#   IMAGE=ghcr.io/<owner>/jumble@sha256:... HOST=jumble-admin@jumble-server deploy/deploy.sh
set -euo pipefail

given_image=${IMAGE:-}
given_ship=${SHIP:-}
given_build=${BUILD:-}
given_mode=${MODE:-}
if [ -n "${INSTANCE:-}" ]; then
  for file in deploy.env jumble.env; do
    test -f "$INSTANCE/$file" || { echo "deploy: $INSTANCE/$file is missing" >&2; exit 1; }
  done
  # shellcheck source=/dev/null # the instance's own settings, on this machine
  . "$INSTANCE/deploy.env"
fi
IMAGE=${given_image:-${IMAGE:-}}
SHIP=${given_ship:-${SHIP:-}}
BUILD=${given_build:-${BUILD:-}}
MODE=${given_mode:-${MODE:-https}}
here=$(cd "$(dirname "$0")" && pwd)
# Checked before anything is built or sent.
"$here/podman/unit.sh" "$MODE" check >/dev/null

if [ -n "$BUILD" ]; then
  root="$here/.."
  revision=$(git -C "$root" rev-parse --short HEAD)
  if [ -n "$(git -C "$root" status --porcelain -- src resources deps.edn build.clj Dockerfile docker litestream.yml)" ]; then
    revision="$revision-modified"
  fi
  # BuildKit: Docker's classic builder cannot build for another platform.
  if ! docker buildx version >/dev/null 2>&1; then
    echo "deploy: BUILD needs docker buildx, to build for linux/amd64 on any machine" >&2
    exit 1
  fi
  echo "deploy: building $revision for linux/amd64"
  docker buildx build --quiet --load --platform linux/amd64 \
    --build-arg JUMBLE_REVISION="$revision" \
    --label "org.opencontainers.image.revision=$revision" --tag jumble:build "$root" >/dev/null
  # Named by its image id, a digest of its content: the same checks apply.
  IMAGE="jumble@$(docker image inspect --format '{{.Id}}' jumble:build)"
  SHIP=1
elif [[ ${IMAGE:?the image to run: <registry>/jumble@sha256:..., a tag such as <registry>/jumble:main, or BUILD=1} != *@sha256:* ]]; then
  if [[ ! $IMAGE =~ ^[a-z0-9./_-]+(:[A-Za-z0-9._-]+)?$ ]]; then
    echo "deploy: IMAGE must name a digest or a tag" >&2
    exit 1
  fi
  echo "deploy: resolving $IMAGE"
  docker pull --quiet --platform linux/amd64 "$IMAGE" >/dev/null
  repository=${IMAGE%:*}
  IMAGE=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$IMAGE" | grep -m1 "^$repository@" || true)
fi
# A digest, and nothing else: the value goes into a script run as root.
if [[ ! $IMAGE =~ ^[a-z0-9./_:-]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "deploy: IMAGE must come to a digest (<registry>/jumble@sha256:<64 hex>), so what runs is exactly what was built" >&2
  exit 1
fi
echo "deploy: image $IMAGE"
: "${HOST:?the server, as user@address (or INSTANCE, a directory whose deploy.env names it)}"

# What Podman runs: the image by digest, or its shipped copy, tagged with
# that digest on the server (Podman names a local image localhost/...).
run_image=$IMAGE
if [ -n "${SHIP:-}" ]; then
  run_image="localhost/jumble:${IMAGE##*@sha256:}"
  if [ -n "$BUILD" ]; then
    docker tag jumble:build "$run_image"
  else
    echo "deploy: pulling $IMAGE"
    docker pull --quiet --platform linux/amd64 "$IMAGE" >/dev/null
    docker tag "$IMAGE" "$run_image"
  fi
  layers=$(docker image inspect --platform linux/amd64 --format '{{json .RootFS.Layers}}' "$run_image")
  echo "deploy: sending it to $HOST"
  docker save --platform linux/amd64 "$run_image" | gzip -1 |
    ssh "$HOST" "gunzip | sudo podman load --quiet >/dev/null"
  # shellcheck disable=SC2029 # expanded here on purpose: derived from the checked digest
  if [ "$(ssh "$HOST" "sudo podman image inspect --format '{{json .RootFS.Layers}}' $run_image")" != "$layers" ]; then
    echo "deploy: the image on $HOST is not the one sent" >&2
    exit 1
  fi
fi

if [ -n "${INSTANCE:-}" ]; then
  # Readable by root only, from the moment it exists; in place only once
  # whole, as a transfer cut short must not leave half of it for a restart.
  ssh "$HOST" "sudo sh -c 'umask 077 && mkdir -p /etc/jumble && cat >/etc/jumble/jumble.env.next && mv /etc/jumble/jumble.env.next /etc/jumble/jumble.env'" <"$INSTANCE/jumble.env"
fi
# The unit (see podman/unit.sh), installed by the script below once the
# image is there.
"$here/podman/unit.sh" "$MODE" "$run_image" |
  ssh "$HOST" "sudo sh -c 'mkdir -p /etc/containers/systemd && cat >/etc/containers/systemd/jumble.container.next'"
ssh "$HOST" "sudo tee /etc/containers/systemd/jumble-data.volume >/dev/null" <"$here/podman/jumble-data.volume"
# shellcheck disable=SC2087 # expanded here on purpose: the values are checked above
ssh "$HOST" sudo bash -s <<EOF
set -euo pipefail
test -f /etc/jumble/jumble.env || { echo "deploy: /etc/jumble/jumble.env is missing" >&2; exit 1; }
cd /etc/containers/systemd
grep -h '^Image=' jumble.container 2>/dev/null | sed 's/^Image=/deploy: previously /' || true
${SHIP:+: }podman pull --quiet "$run_image" >/dev/null
mv jumble.container.next jumble.container
systemctl daemon-reload
# Waits for the app's health check (the unit's ExecStartPost): a first
# start restoring the database from its backup can take minutes.
if systemctl restart jumble; then
  echo "deploy: running $IMAGE"
  # Images no container uses: rolling back deploys the previous digest again.
  podman image prune --all --force >/dev/null
else
  echo "deploy: not healthy after 7 minutes; logs:" >&2
  journalctl -u jumble -n 50 --no-pager >&2
  exit 1
fi
EOF
