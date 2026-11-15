#!/usr/bin/env bash
# Prepares a server from any provider for Jumble, over SSH: runs
# server-setup.sh on it (Podman, SSH by key only, automatic security
# updates). The server runs Ubuntu or Debian and takes your SSH key.
#
# Connected as root, as some providers only allow at first, it first makes
# an admin user (ADMIN_USER, jumble-admin by default) taking the same keys,
# with sudo, and checks it can log in before root no longer can: deploy.sh
# then connects as that user, written into the instance's deploy.env.
#
# With INSTANCE, deploy.env there names the server (HOST); otherwise HOST is
# given. Running it again changes nothing.
#
#   INSTANCE=deploy/instances/community deploy/bootstrap.sh
#   HOST=root@203.0.113.7 deploy/bootstrap.sh
set -euo pipefail

if [ -n "${INSTANCE:-}" ] && [ -z "${HOST:-}" ]; then
  test -f "$INSTANCE/deploy.env" || { echo "bootstrap: $INSTANCE/deploy.env is missing" >&2; exit 1; }
  # shellcheck source=/dev/null # the instance's own settings, on this machine
  . "$INSTANCE/deploy.env"
fi
: "${HOST:?the server, as user@address}"
admin=${ADMIN_USER:-jumble-admin}
here=$(cd "$(dirname "$0")" && pwd)
address=${HOST#*@}

if [ "$(ssh "$HOST" id -u)" = 0 ]; then
  echo "bootstrap: connected as root; making $admin, with root's keys and sudo"
  # shellcheck disable=SC2087 # $admin is meant to expand here, on this side
  ssh "$HOST" bash -s <<EOF
set -euo pipefail
id "$admin" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$admin"
install -d -m 700 -o "$admin" -g "$admin" "/home/$admin/.ssh"
install -m 600 -o "$admin" -g "$admin" /root/.ssh/authorized_keys "/home/$admin/.ssh/authorized_keys"
echo "$admin ALL=(ALL) NOPASSWD:ALL" >"/etc/sudoers.d/90-$admin"
chmod 440 "/etc/sudoers.d/90-$admin"
visudo -cf "/etc/sudoers.d/90-$admin" >/dev/null
EOF
  HOST="$admin@$address"
fi

# Root login ends below: the user deploy.sh connects as must work first.
if ! ssh "$HOST" sudo -n true; then
  echo "bootstrap: $HOST cannot use sudo without a password, which deploy.sh needs" >&2
  exit 1
fi
ssh "$HOST" "sudo bash -s -- \"\$(id -un)\"" <"$here/server-setup.sh"

if [ -n "${INSTANCE:-}" ]; then
  sed -i.previous "s/^HOST=.*/HOST=$HOST/" "$INSTANCE/deploy.env" && rm -f "$INSTANCE/deploy.env.previous"
  echo "bootstrap: done. deploy.sh connects as $HOST, as $INSTANCE/deploy.env says"
else
  echo "bootstrap: done. deploy.sh connects as $HOST:"
  echo "  HOST=$HOST in your instance's deploy.env"
fi
