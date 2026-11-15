# Deployment

A Jumble is one container on one server, serving HTTPS itself with a
certificate it gets from [Let's Encrypt](https://letsencrypt.org) and
renews (or behind a reverse proxy of your own). The container runs the app and
[Litestream](https://litestream.io), which copies every change to the
SQLite database to a backup bucket within a second. Photos live in a bucket
of their own.

```
members ──https :443──> jumble container ──> photo bucket
                         └─ Litestream ───> backup bucket (versioned)
           sign-in provider <── app ──> SMTP server
```

There is exactly one container per community: SQLite has a single writer,
and two containers on the same backup path would overwrite each other's
backups. Plan for a few minutes of downtime on upgrades.

New to this? The [quickstart](quickstart.md) suggests a service for each
need and takes you through it in about half an hour.

There are two ways to get one running: on a cloud provider, in one
command, or on any server with any S3-compatible storage. Both end the same
way: a server prepared by [`deploy/bootstrap.sh`](../deploy/bootstrap.sh),
the settings kept on your machine in `deploy/instances/<name>/`, and
[`deploy/deploy.sh`](../deploy/deploy.sh) running Jumble there.

## On a cloud provider

The quickest way: the scripts provided do everything on the provider's
side, so you can be up and running in minutes. `deploy/provision.sh`
creates the server, the photo and backup buckets and their keys, and
prepares the server, on Exoscale (well tested), AWS or Scaleway (both
experimental); you fill in how members sign in and how email is sent, and
deploy:

```sh
SSH_KEY=<key name> deploy/provision.sh exoscale community
# fill in sign-in, who may sign in, the moderators and SMTP:
$EDITOR deploy/instances/community/jumble.env
deploy/verify-keys.sh deploy/instances/community/jumble.env
INSTANCE=deploy/instances/community deploy/deploy.sh
```

Jumble is then at `https://<the server's address, dashes for dots>.sslip.io`,
or the `DOMAIN` you gave. What each provider needs and creates, and how to
remove it all (`deploy/teardown.sh exoscale community`), is in
[cloud providers](cloud-providers.md).

## On any server

Any server running Ubuntu or Debian and taking your SSH key, and any
S3-compatible storage that keeps object versions (AWS S3, Exoscale,
Scaleway, Hetzner, Backblaze B2, MinIO…):

1. **Storage**: two private buckets, one for photos and one for backups,
   the backup one versioned with old versions expiring after 90 days; and a
   key for each, limited to its bucket, the backup one unable to erase a
   version. See [storage](deployment-reference.md#1-storage).
2. **Server**: `HOST=root@<address> deploy/bootstrap.sh` installs Podman,
   locks SSH down to keys and turns on security updates; connected as root,
   it makes the admin user to connect as from then on. See
   [the server](deployment-reference.md#4-the-server).
3. **Settings**, in `deploy/instances/community/` on your machine:
   `deploy.env` naming the server (`HOST=<user>@<address>`, as
   `bootstrap.sh` says) with `BUILD=1`, and `jumble.env` from
   [`deploy/jumble.env.example`](../deploy/jumble.env.example), filled in:
   the address, the storage, sign-in and who may sign in, the moderators,
   SMTP. See [configuration](configuration.md), and
   [sign-in](deployment-reference.md#2-sign-in) and
   [email](deployment-reference.md#3-email).
4. **Check the keys**: `deploy/verify-keys.sh deploy/instances/community/jumble.env`
   must pass.
5. **Domain**: point it at the server (an A or AAAA record), and let ports
   80 and 443 in (TCP, and UDP 443). Jumble gets its certificate from Let's
   Encrypt.
6. **Deploy**: `INSTANCE=deploy/instances/community deploy/deploy.sh`
   builds the image on your machine, sends it over SSH, starts it and waits
   until it is healthy. To deploy an image from a registry instead, see
   [the image](deployment-reference.md#6-the-image).

Behind a reverse proxy of your own (nginx, a load balancer…), deploy with
`MODE=own-proxy`: see [with a proxy of your own](deployment-reference.md#with-a-proxy-of-your-own).
[Step by step](deployment-reference.md) has the details behind each step,
and how to do them by hand.

## Once it runs

On first start the database is created (or restored from the backup bucket,
if it has one). Open Jumble's address, sign in, and check:

- the moderators see **Manage** in the header;
- a test listing with a photo shows its photo;
- a message to someone, left unread for five minutes, arrives by email.

Keep a copy of `deploy/instances/community/` somewhere safe, a password
manager say: with the backups, it is all a new server needs. Then see
[running it](operations.md): upgrading, logs, an uptime monitor and a
backup heartbeat, restoring, and the security checklist.
