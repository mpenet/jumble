# Deployment, step by step

What each step of [deployment](deployment.md) involves, for a server and
storage set up by hand, or to know what the scripts do.

## What you need

- **A server** with systemd and [Podman](https://podman.io) 4.4 or later,
  which runs Jumble as a systemd service through its Quadlet units (Quadlet
  is Podman's way of running containers as systemd services): Ubuntu
  24.04 (Podman 4.9) or Debian 13, say.
  Jumble is built for a small one: 1 CPU, 2 GB of memory, a few GB of disk
  (the database is small; photos are in object storage).
- **A domain name** for Jumble, pointing at the server (an A or AAAA
  record): Jumble gets its certificate from Let's Encrypt. Or a reverse
  proxy or load balancer of your own, with its certificate.
- **S3-compatible object storage** with two private buckets.
- **An SMTP account** to send email; with email sign-in, that is all sign-in
  needs. Otherwise, **an OpenID Connect provider**.
[Configuration](configuration.md) covers the choices for each.

The server must reach, outbound: the sign-in provider, the storage
endpoint, the SMTP server, the image registry, and its operating system's
updates, and Let's Encrypt. Inbound, members reach ports 80 and 443 (TCP,
and UDP 443 for HTTP/3), and you SSH. With a proxy of your own, only it
reaches port 8080 instead.

## 1. Storage

Create two private buckets in the same region: one for photos, one for
backups. Turn on **versioning** for the backup bucket, with a lifecycle
rule expiring noncurrent versions after, say, 90 days: deleted accounts
then leave the backups too, while a mistake stays recoverable for three
months. Then create two keys:

- one for photos: read, write and delete objects, in the photo bucket only;
- one for backups: read, write, list and delete objects in the backup
  bucket only, but neither delete object versions nor change versioning.

With versioning on and that limit, nothing done with the backup key, by
Litestream pruning old data or by someone who stole the key, erases the
backup history. Any S3-compatible storage does (AWS S3, Exoscale,
Scaleway, Hetzner, Backblaze B2, MinIO…) as long as it keeps object
versions and lets a key be limited this way. Check the keys before going
further:

```sh
deploy/verify-keys.sh jumble.env
```

It reads the keys, endpoint and buckets from the env file of step 4
(`ENDPOINT`, `REGION`, `PHOTOS_BUCKET` and `BACKUP_BUCKET` override them), and tries
what each key must and must not be able to do, including erasing a backup
version. Everything must pass. It leaves one tiny object version under `verify/` in the backup
bucket, the proof that it could not be erased.

## 2. Sign-in

Choose how members sign in, and who may: see [signing in](configuration.md#signing-in).
With email sign-in there is nothing to set up beyond email itself. With an
OpenID Connect provider, register Jumble with it as a web application, with
`https://<your address>/auth/callback` as redirect URI; there are
step-by-step instructions for Google Workspace.

## 3. Email

Get SMTP credentials and a sender address. See
[email providers](configuration.md#email-providers).

## 4. The server

Any server from any provider, running Ubuntu or Debian and taking your SSH
key, prepared from your machine:

```sh
HOST=root@203.0.113.7 deploy/bootstrap.sh
```

It runs [`deploy/server-setup.sh`](../deploy/server-setup.sh) on the server:
Podman; SSH by key only, no password and no root; security updates
installing themselves, restarting the server at 04:00 when one needs it;
and `/etc/jumble` for the settings. Connected as root, as some providers
allow at first, it makes the user `jumble-admin` (or `ADMIN_USER`) with
your keys and sudo, and checks it can log in before root no longer can;
from then on, connect as that user. It stops, changing nothing in SSH, if
another file of the server's settings would override these, and says
which. Running it again changes nothing. With an instance directory
(below), `INSTANCE=deploy/instances/<name> deploy/bootstrap.sh` reads the
server from its `deploy.env`.

These scripts connect with plain `ssh`, which offers your default keys
(`~/.ssh/id_*`). A key of another name must be in your SSH agent
(`ssh-add ~/.ssh/<key>`) or named for the server in `~/.ssh/config`
(`IdentityFile`); otherwise the server answers `Permission denied
(publickey)`.

Then copy [`deploy/jumble.env.example`](../deploy/jumble.env.example) to
`/etc/jumble/jumble.env`, fill it in, and protect it:

```sh
sudo chown root:root /etc/jumble/jumble.env
sudo chmod 600 /etc/jumble/jumble.env
```

Choose the currency now: it cannot change once Jumble has started. Values
are taken as they are, up to the end of the line: no quotes around them,
and a `$` is a `$`.

To give the JVM more options (more memory for a bigger community, say),
set `JAVA_OPTS` in the env file; they add to the flags the image starts
Java with. Without them the heap may grow to 75% of the memory the
container sees, the whole machine's unless the container is limited.
Photos are processed outside the JVM, so a small heap goes a long way:
with `JAVA_OPTS=-Xmx96m -XX:+UseSerialGC`, the container stayed under 200
MB through a test of uploads (40 megapixel photos included) and browsing.
Watch it with your community's use before going lower.

For settings without an environment variable, write a
[settings file](configuration.md#settings-file) to `/etc/jumble/jumble.edn`,
set `JUMBLE_CONFIG_FILE=/config/jumble.edn` in the env file, and mount it
by adding to the `[Container]` section of `deploy/podman/jumble.container`
(in your copy of this repository, which `deploy.sh` installs on the
server):

```ini
Volume=/etc/jumble/jumble.edn:/config/jumble.edn:ro
```

For the community's logo, put it in `/etc/jumble/` too, mount it the same
way (`Volume=/etc/jumble/logo.svg:/config/logo.svg:ro`) and set
`JUMBLE_LOGO=/config/logo.svg` (and `JUMBLE_LOGO_DARK` for a dark-theme
version). Jumble checks it at startup: SVG or PNG, at most 200 KB. An SVG is
shown as an image and served so that it cannot run scripts even when opened
on its own.

## 5. HTTPS

### Served by Jumble

By default ([`deploy/podman/https.conf`](../deploy/podman/https.conf)), Jumble serves HTTPS itself
on port 443, HTTP/2 over TCP and HTTP/3 over UDP, with a certificate for
`JUMBLE_BASE_URL`'s host from Let's Encrypt. Port 80 answers Let's
Encrypt's challenges and redirects everything else to https. Point the
domain at the server and open ports 80 and 443 (TCP, and UDP 443): nothing
else to set. Optionally, give Let's Encrypt an address for the certificate
with `JUMBLE_ACME_EMAIL`.

The certificate is obtained in the background: the app starts at once,
and HTTPS with it once the certificate is in, within seconds when the
domain already points at the server. A failure is logged and tried again
later, so a domain not yet pointing at the server only delays HTTPS; until
there is a certificate, a warning is logged every hour. The health check
answers on port 80 meanwhile, so check the logs after a first deploy. The
certificate is renewed well before it expires, without a restart. It is
kept, with the Let's Encrypt account, in `/data/certificates` on the data
volume: a new server gets a new one, which Let's Encrypt's limits allow a
few times a week.

### With a proxy of your own

Deploy with `MODE=own-proxy` instead (in the instance's `deploy.env`, or on
`deploy.sh`'s command line):
[`deploy/podman/own-proxy.conf`](../deploy/podman/own-proxy.conf) has Jumble
serve plain HTTP on port 8080 of the loopback. The proxy must:

- serve Jumble's address over HTTPS and forward to port 8080 over HTTP,
  passing the original `Host` header, and adding the client's address to
  `X-Forwarded-For`;
- accept request bodies up to 64 MB, with time for slow uploads;
- optionally, check health with `GET /healthz`, which answers 200 while the
  app and its database work.

Jumble sets its own security headers (including HSTS) and builds every
URL from `JUMBLE_BASE_URL`. Of the forwarded headers it reads only the
last address in `X-Forwarded-For`, the one the proxy added, as the
client's: limits such as how many sign-in links one client may ask for
apply to it. `own-proxy.conf` turns this on (`JUMBLE_BEHIND_PROXY=true`);
it is why only the proxy may reach Jumble.

With nginx on the same server:

```nginx
server {
    listen 443 ssl;
    server_name jumble.example.org;
    # ssl_certificate and ssl_certificate_key as you manage them

    client_max_body_size 64m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
}
```

Only the proxy must reach port 8080. `own-proxy.conf` publishes it on the
loopback address, right for a proxy on the same server. For a proxy
elsewhere, publish it on the private address the proxy reaches
(`PublishPort=10.0.0.5:8080:8080`) and allow only the proxy, with your
cloud's firewall or on the server: Podman's published ports are forwarded
before host firewalls such as ufw see them, so filter in the `FORWARD`
chain:

```sh
sudo iptables -I FORWARD -p tcp -m conntrack --ctorigdstport 8080 --ctdir ORIGINAL ! -s <proxy address> -j DROP
```

(and make it persistent the way your distribution does).

## 6. The image

Build it from this repository:

```sh
docker build -t jumble .   # or podman build
```

or let the [workflow](../.github/workflows/image.yml) build it: on GitHub,
every push to `main` that passes the tests pushes an image to the
repository's container registry, and the run's summary gives its digest.
For a private registry, log the server in once, with a token that can only
read packages:

```sh
sudo podman login ghcr.io -u <user>
```

Deploy by digest (`ghcr.io/<owner>/jumble@sha256:…`), not by tag: then what
runs is exactly what was built and tested.

## 7. Starting it

From your machine, with SSH access to the server. The simplest is to keep
the deployment's configuration on your machine, in a directory of
`deploy/instances/` (ignored by git), and let `deploy.sh` install it:

```
deploy/instances/community/
  deploy.env    # HOST=jumble-admin@jumble-server
  jumble.env    # the settings of step 4, secrets included: mode 600
```

```sh
IMAGE=ghcr.io/<owner>/jumble@sha256:<digest> INSTANCE=deploy/instances/community deploy/deploy.sh
```

A changed setting is then edited there and deployed again. With `SHIP=1`,
the server needs no access to a private registry: the image is pulled on
your machine, sent over SSH, checked to be the same there layer by layer,
and run from that copy. Keep a copy of
that directory somewhere safe, a password manager say: with the backups, it
is all a new server needs. To manage `/etc/jumble/jumble.env` on the
server instead, name the server alone:

```sh
IMAGE=ghcr.io/<owner>/jumble@sha256:<digest> HOST=jumble-admin@jumble-server deploy/deploy.sh
```

[`deploy/deploy.sh`](../deploy/deploy.sh) installs
[`deploy/podman/`](../deploy/podman/)'s units in `/etc/containers/systemd/`,
`jumble.container` with the mode's settings and the image appended, then
restarts the `jumble` service, which counts as started once the app
answers its health check. The SSH user needs `sudo` without a password
prompt on the server. On the server by hand, the same is:

```sh
cd podman                  # a copy of deploy/podman/ on the server
sudo cp jumble-data.volume /etc/containers/systemd/
{ cat jumble.container https.conf; printf '[Container]\nImage=<image>@sha256:<digest>\n'; } |
  sudo tee /etc/containers/systemd/jumble.container >/dev/null
sudo systemctl daemon-reload
sudo systemctl restart jumble   # waits until healthy, at most 7 minutes
```

On first start the database is created (or restored from the backup bucket,
if it has one); then check it as [once it runs](deployment.md#once-it-runs)
says.
