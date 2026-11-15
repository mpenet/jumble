#!/usr/bin/env bash
# Prepares an Ubuntu or Debian server for Jumble, run as root on it: Podman,
# which deploy.sh runs Jumble with as a systemd service, and the directory of
# its settings. SSH takes keys only, root cannot log in, and the admin user's
# password is locked (sudo must not ask for it). Security updates install
# themselves, restarting the server at 04:00 when one needs it.
#
# The same on any provider: bootstrap.sh runs it over SSH. Given as an
# Ubuntu server's cloud-init user-data, it runs at first boot instead.
# Running it again changes nothing.
#
#   deploy/server-setup.sh [ADMIN_USER]
#
# ADMIN_USER, the user deploy.sh connects as, defaults to the one running
# sudo, else ubuntu (the user of Ubuntu's cloud images).
set -euo pipefail

admin=${1:-${SUDO_USER:-ubuntu}}

if [ "$(id -u)" != 0 ]; then
  echo "server-setup: run as root" >&2
  exit 1
fi
# shellcheck source=/dev/null # the server's own description of itself
. /etc/os-release
if [[ " ${ID:-} ${ID_LIKE:-} " != *" debian "* && "${ID:-}" != ubuntu ]]; then
  echo "server-setup: needs Ubuntu or Debian, not ${PRETTY_NAME:-this system}" >&2
  exit 1
fi
if ! id "$admin" >/dev/null 2>&1; then
  echo "server-setup: no user $admin, whom deploy.sh connects as" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
# At first boot the system's own updates may hold apt's lock: waited for.
apt_get() {
  apt-get -o DPkg::Lock::Timeout=600 -y "$@"
}
apt_get update
apt_get install podman unattended-upgrades

install -d -m 755 /etc/ssh/sshd_config.d
cat >/etc/ssh/sshd_config.d/10-jumble.conf <<'EOF'
# Read before the image's own settings, so that these win: keys only, no
# root, nothing forwarded to the server's side.
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
X11Forwarding no
AllowAgentForwarding no
EOF

cat >/etc/apt/apt.conf.d/52jumble-reboot <<'EOF'
// Updates needing a restart (the kernel's) take effect: at 04:00, the
// containers starting again with the server.
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
EOF
cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

# A password the platform may have set: locked, so it cannot be guessed;
# sudo does not ask for it.
passwd -l "$admin" >/dev/null
# Checked before it applies: a mistake here would lock everyone out. sshd
# keeps the first value it reads, so a file of the image's read before ours
# would win: the settings in effect must be ours.
sshd -t
wanted="allowagentforwarding no
kbdinteractiveauthentication no
passwordauthentication no
permitrootlogin no
x11forwarding no"
in_effect=$(sshd -T | grep -E '^(allowagentforwarding|kbdinteractiveauthentication|passwordauthentication|permitrootlogin|x11forwarding) ' | sort)
if [ "$in_effect" != "$wanted" ]; then
  echo "server-setup: SSH would not be locked down, other settings win:" >&2
  { diff <(echo "$wanted") <(echo "$in_effect") || true; } | sed -n 's/^> /  /p' >&2
  echo "set in:" >&2
  grep -l -i -E '^\s*(AllowAgentForwarding|KbdInteractiveAuthentication|PasswordAuthentication|PermitRootLogin|X11Forwarding)\s' \
    /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf | { grep -v 10-jumble.conf || true; } | sed 's/^/  /' >&2
  exit 1
fi
systemctl reload ssh 2>/dev/null || systemctl reload sshd

install -d -m 700 /etc/jumble
echo "server-setup: ready for deploy.sh, as $admin"
