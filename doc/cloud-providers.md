# On a cloud provider

`deploy/provision.sh PROVIDER NAME` sets everything up in one go, on
Exoscale, AWS or Scaleway, so you can be up and running in minutes: the
server, its storage and keys, and HTTPS. Exoscale's is well tested: it runs the demo, and
is checked end to end, from creating to tearing down. AWS's and Scaleway's
are **experimental**: reviewed against their CLIs' documentation, but not
yet run against a real account; expect to fix a step on a first run, and
check what is left with the provider's console if one stops halfway.

Each sets up the two buckets and their keys ([storage](deployment-reference.md#1-storage)), the server prepared
by `bootstrap.sh` ([the server](deployment-reference.md#4-the-server)), and HTTPS served by Jumble
([HTTPS](deployment-reference.md#5-https)). Each provider's CLI runs with a configuration of Jumble's own,
under `~/.config/jumble/`, so whatever other accounts it knows stay out of
reach. The settings go to `deploy/instances/NAME/`, on your machine only; a
key's secret is shown once, when it is made, so keep that directory. Run
again, it creates what is missing and leaves the rest.
`deploy/teardown.sh PROVIDER NAME` removes it all, photos and backups
included, after asking for the name again. The SSH key given must be one
`ssh` offers a new address: your default, or one in your SSH agent (see
[the server](deployment-reference.md#4-the-server)).

## Exoscale

`deploy/exoscale/` needs the [`exo` CLI](https://community.exoscale.com/tools/command-line-interface/),
recent enough to set bucket lifecycles (1.102 is; 1.94 is not), an SSH key registered with Exoscale (`exo compute ssh-key list`), and an
SMTP account of your own: Exoscale sends no email. The SSH key must be one
`ssh` offers a new address: your default, or one in your SSH agent (see
[the server](deployment-reference.md#4-the-server)).

The scripts use an `exo` configuration of their own,
`~/.config/jumble/exoscale.toml` unless `EXOSCALE_CONFIG` names another, so
whatever other accounts the CLI knows stay out of reach. Make it once, with
the API key of the account Jumble goes in; `exo` adds to a file only once it
exists:

```sh
mkdir -p ~/.config/jumble && install -m 600 /dev/null ~/.config/jumble/exoscale.toml
EXOSCALE_CONFIG=~/.config/jumble/exoscale.toml exo config add
```

Its default account is used, or `EXOSCALE_ACCOUNT`. Then:

```sh
SSH_KEY=<ssh key name> deploy/provision.sh exoscale community
```

This creates, in zone `ch-gva-2` unless `ZONE` says otherwise:

- the buckets `community-<random>-photos` and `community-<random>-backup`,
  the second versioned, its old versions expiring after 90 days, as the
  privacy page tells members. Bucket names are shared by everyone on Exoscale, so
  they are made unique, once, and kept in
  `deploy/instances/community/exoscale.env` before anything is created;
- a key for each, through an IAM role of the same name. The photos key may
  only store, read and delete objects in the photos bucket; the backup key
  may also list its bucket, but not delete an object version, nor change
  versioning, so a leaked key cannot erase the backup history;
- the security group `community`: SSH (from `ADMIN_CIDR`, anywhere unless
  set), HTTP, HTTPS and HTTP/3 (UDP 443);
- the instance `community`: Ubuntu 24.04, `standard.small` and 20 GB
  unless `INSTANCE_TYPE` and `DISK_SIZE` say otherwise, prepared by
  `bootstrap.sh` once it answers, as any server is
([the server](deployment-reference.md#4-the-server)).

Its configuration goes to `deploy/instances/community/`, on your machine
only: `deploy.env`, the server for `deploy.sh`, and `jumble.env`, the
settings with the buckets and keys filled in. A key's secret is shown once,
when it is made, so keep that directory. The address is
`https://<the server's address, dashes for dots>.sslip.io`, which needs no
DNS and still gets a certificate; `DOMAIN` sets your own instead, its DNS
record pointing at the server. Run again, the script creates what is
missing and leaves the rest.

Then fill in the rest of `jumble.env` (how members sign in, who may, the
moderators and SMTP), check the keys with `deploy/verify-keys.sh
deploy/instances/community/jumble.env`, and deploy:

```sh
INSTANCE=deploy/instances/community deploy/deploy.sh
```

`deploy.sh` builds the image here, from this checkout, for the server's
platform (`BUILD=1` in `deploy.env`), and sends it over SSH: no registry is
involved. On an ARM machine (Apple silicon) the build runs emulated and takes
a few minutes. Set `IMAGE` instead to run one CI built.

To make it look lived in, [`dev/demo_seed.clj`](../dev/demo_seed.clj) adds
the members and listings of [`dev/demo/listings.edn`](../dev/demo/listings.edn),
with real photos (public domain or CC0, from Openverse). The members are
email accounts at a domain of yours, which the community must let in
(moderators can add `*@<domain>`), with message and weekly emails off; their
mailboxes need not exist, but give the domain a catch-all that drops mail,
or reminders to them bounce. On the server, from a copy of `dev/`:

```sh
image=$(sed -n 's/^Image=//p' /etc/containers/systemd/jumble.container)
sudo podman run --rm --env-file /etc/jumble/jumble.env -v jumble-data:/data -v "$PWD/dev:/seed:ro" \
  --entrypoint java "$image" -Djumble.demo=true -cp /app/jumble.jar:/seed \
  clojure.main -m demo-seed /seed/demo/listings.edn <domain>
```

Run again, it adds only the listings missing.

To reset it every night, as demo.jumbl.ing is, set
`JUMBLE_NOTICE_FILE=/data/notice.md` in the instance's `jumble.env`,
deploy, then:

```sh
HOST=jumble-admin@<server> DOMAIN=<domain> deploy/demo/install.sh
```

A timer then runs [`dev/demo_reset.clj`](../dev/demo_reset.clj) at 04:00:
it deletes every account but the demo members' and the moderators', with
what they left on the others' side, and seeds again what is missing. It
refuses to run where the demo seed has not marked the community as a demo
for that domain. The
[notice](../deploy/demo/notice.md) at the top of every page says so.

To remove it all, the photos and backups with it:

```sh
deploy/teardown.sh exoscale community
```

It finds what to delete in `deploy/instances/community/exoscale.env`, asks
for the name again before deleting anything, and leaves that directory,
whose keys are then void.

## AWS (experimental)

`deploy/aws/` needs the [`aws` CLI](https://aws.amazon.com/cli/) and an EC2
key pair in the region (`aws ec2 describe-key-pairs`). Its configuration
is `~/.config/jumble/aws/` (`config` and `credentials`), made with the
account Jumble goes in, with rights over EC2, S3, IAM and reading SSM
parameters:

```sh
AWS_CONFIG_FILE=~/.config/jumble/aws/config \
AWS_SHARED_CREDENTIALS_FILE=~/.config/jumble/aws/credentials aws configure
SSH_KEY=<key pair name> deploy/provision.sh aws community
```

In `eu-central-1` unless `REGION` says otherwise, it creates the two
buckets (private, the backup one versioned, its old versions expiring after
90 days), an IAM user for each with a key limited to its bucket (the backup
user explicitly refused erasing versions and changing versioning or the
lifecycle), the security group `community`, and the instance:
Canonical's current Ubuntu 24.04, `t3.small` and 20 GB (encrypted) unless
`INSTANCE_TYPE` and `DISK_SIZE` say otherwise, in the default VPC. Its
public address changes if the instance is stopped and started; give it an
Elastic IP for a lasting one, with `DOMAIN` set to a name pointing at it.
Amazon SES, or any SMTP account, sends the email. Keys or a region already
in your shell's environment are ignored: only that configuration is used.

## Scaleway (experimental)

`deploy/scaleway/` needs the [`scw` CLI](https://www.scaleway.com/en/cli/),
and the [`aws` CLI](https://aws.amazon.com/cli/), which sets up the buckets
through Scaleway's S3 API. Its configuration is `~/.config/jumble/scw.yaml`,
made with the project Jumble goes in, whose SSH keys reach the server. Give
Jumble a project of its own: Scaleway lets each key reach every bucket of
its project, and only Jumble's two buckets have policies narrowing that.

```sh
SCW_CONFIG_PATH=~/.config/jumble/scw.yaml scw init
deploy/provision.sh scaleway community
```

In zone `fr-par-1` unless `ZONE` says otherwise, it creates the two buckets
(the backup one versioned, its old versions expiring after 90 days), an
IAM application for each with a key, each bucket's policy letting in only
its own application (the backup one may not erase versions), the security
group `community`, and the instance: Ubuntu 24.04, `DEV1-S` unless
`INSTANCE_TYPE` says otherwise. Scaleway's Ubuntu lets root in at first;
`bootstrap.sh` makes `jumble-admin` and writes it into `deploy.env`.
Scaleway refuses mail out of its servers on ports 25, 465 and 587 until
the account is verified and the block lifted; Scaleway Transactional Email
also listens on port 2465, which works from the start.
