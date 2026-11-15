# deploy/

Files for running Jumble in production, described in
[doc/deployment.md](../doc/deployment.md):

- `podman/`: Jumble as the systemd service `jumble`, through Podman's
  Quadlet: `jumble.container` (the container and its hardening),
  `jumble-data.volume`, and the settings of each mode, `https.conf`
  (Jumble serving HTTPS itself, with a certificate from Let's Encrypt) and
  `own-proxy.conf` (behind a reverse proxy of your own).
- `jumble.env.example`: the settings to fill in, as `/etc/jumble/jumble.env`.
- `deploy.sh`: runs an image, by digest, on the server and waits until it is
  healthy; `MODE=own-proxy` for a proxy of your own.
- `bootstrap.sh`: prepares a server from any provider over SSH, by running
  `server-setup.sh` on it (Podman, SSH by key only, automatic security
  updates); connected as root, it makes an admin user first.
- `server-setup.sh`: that preparation, run as root on the server.
- `restore-drill.sh`: restores the latest backup into a scratch volume on
  the server and checks it against the live database, Jumble running on.
- `verify-keys.sh`: checks the storage keys can do what Jumble needs and no
  more, on any S3-compatible storage.
- `provision.sh PROVIDER NAME`: a Jumble on a cloud provider in one go: the
  server (prepared by `bootstrap.sh`), the photos bucket, the versioned
  backup bucket and a key limited to each, written to `instances/NAME` for
  `deploy.sh`; `teardown.sh PROVIDER NAME` deletes all of it. Each provider
  has its own directory, `exoscale/` (well tested), `aws/` and `scaleway/`
  (experimental: not yet run against a real account), using its CLI
  with a configuration of its own under `~/.config/jumble/`; `lib.sh` holds
  what they share. See
  [On a cloud provider](../doc/cloud-providers.md).
- `instances/` (ignored by git): one directory per deployment, holding its
  configuration on your machine, secrets included: `deploy.env` (the server)
  and `jumble.env` (installed as `/etc/jumble/jumble.env`).
  `INSTANCE=deploy/instances/<name> deploy/deploy.sh` deploys from it;
  `SHIP=1` sends the image over SSH, for servers without registry access;
  `BUILD=1` builds it here from the checkout and sends it, no registry at
  all. `deploy.env` may set `IMAGE` (a tag such as
  `ghcr.io/<owner>/jumble:main` resolves to its digest), `SHIP` and `BUILD`
  as defaults.
