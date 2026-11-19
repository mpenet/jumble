#!/usr/bin/env bash
# Makes a deployed Jumble a demo reset every night: installs dev/demo_reset.clj,
# the seed and its listings, and the systemd timer running them, on HOST.
#
# Members at DOMAIN are the demo's (see dev/demo_seed.clj); the owner and
# moderators stay too. Everyone else is deleted nightly. The notice telling visitors so
# is installed as notice.md in the data volume, for JUMBLE_NOTICE_FILE=/data/notice.md.
#
#   HOST=jumble-admin@jumble-demo DOMAIN=jumbl.ing deploy/demo/install.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
: "${HOST:?the server, as for ssh}" "${DOMAIN:?the demo members domain}"
if [[ ! $DOMAIN =~ ^[a-z0-9.-]+$ ]]; then
  echo "install: DOMAIN must be a domain name" >&2
  exit 1
fi

echo "install: copying the reset and the seed to $HOST"
tar -C "$root/dev" -cf - demo_reset.clj demo_seed.clj demo/listings.edn |
  ssh "$HOST" "sudo sh -c 'rm -rf /opt/jumble-demo.next && mkdir -p /opt/jumble-demo.next && tar -C /opt/jumble-demo.next --no-same-owner -xf - && chmod -R a+rX /opt/jumble-demo.next'"
# shellcheck disable=SC2029 # expanded here on purpose: checked to be a domain name above
ssh "$HOST" "sudo sh -c 'echo JUMBLE_DEMO_DOMAIN=$DOMAIN >/opt/jumble-demo.next/demo.env && rm -rf /opt/jumble-demo && mv /opt/jumble-demo.next /opt/jumble-demo'"

echo "install: the notice and the timer"
ssh "$HOST" "sudo sh -c 'cat >\"\$(podman volume inspect --format \"{{.Mountpoint}}\" jumble-data)/notice.md\"'" <"$here/notice.md"
for unit in jumble-demo-reset.service jumble-demo-reset.timer; do
  # shellcheck disable=SC2029 # expanded here on purpose: one of the two units above
  ssh "$HOST" "sudo tee /etc/systemd/system/$unit >/dev/null" <"$here/$unit"
done
ssh "$HOST" "sudo systemctl daemon-reload && sudo systemctl enable --now jumble-demo-reset.timer"
echo "install: done; the next reset: $(ssh "$HOST" "systemctl show jumble-demo-reset.timer --property NextElapseUSecRealtime --value")"
