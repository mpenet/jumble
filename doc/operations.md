# Running it

Upgrading, health and logs, backups, rotating keys, and a security
checklist, for a Jumble [deployed](deployment.md).

## Upgrading

Deploy the new image the same way. `IMAGE` may name a tag rather than a
digest: `ghcr.io/<owner>/jumble:main` is the latest image the workflow
tested and pushed, `ghcr.io/<owner>/jumble:<commit>` that commit's.
`deploy.sh` resolves it to its digest, which is what runs, and prints it.
With `IMAGE` (and `SHIP=1`) in the instance's `deploy.env`, upgrading is:

```sh
INSTANCE=deploy/instances/community deploy/deploy.sh
```

Database migrations run on
start, before Jumble serves anything.

### Rolling back

`deploy.sh` prints the image that ran before (`deploy: previously …`).
When the new one misbehaves, deploy that one again:

```sh
IMAGE=ghcr.io/<owner>/jumble@sha256:<previous> INSTANCE=deploy/instances/community deploy/deploy.sh
```

This works unless the upgrade migrated the database: an older version
refuses to start on a newer database, logging `Database schema is newer
than this code`, and `deploy.sh` reports the failed health check. Deploy
the newer image again then, and fix forward with another; or, when the data
itself went wrong, restore the database to before the upgrade (see
[backups](#backups)), with the previous image.

## Health and logs

`GET /healthz` answers 200 while the app and its database work. Podman
checks it every 30 seconds (`deploy/podman/jumble.container`), allowing five
minutes for a first start that restores a backup; three checks failed in
a row, it stops the container, and systemd starts it again, so an app that
hangs rather than exits does not stay down. `sudo podman ps` shows the
result, `systemctl status jumble` the service. Logs go to the journal:

```sh
journalctl -u jumble -f
```

They record failures and refused sign-ins. Jumble names email domains
rather than addresses in what it writes there, and never message contents;
an error reported by the SMTP server may quote a recipient's address.

To be told when Jumble is down, point an uptime monitor (UptimeRobot,
Better Stack and others have free plans) at `https://<your address>/healthz`
every few minutes. Over HTTPS it also catches a certificate that failed to
renew, once the old one expires. Backups need a monitor of their own (see
below): the site keeps working when they stop.

## Metrics

With `JUMBLE_METRICS_TOKEN` set, `GET /metrics` answers the token's bearer
in Prometheus' text format:

- the community: `jumble_members`, `jumble_listings` by status;
- activity, counted since the start: `jumble_listings_created_total`;
  `jumble_listings_closed_total` by outcome (sold, given, found, expired,
  deleted by their owner, removed by a moderator);
  `jumble_messages_sent_total` by kind: first (a conversation started) or
  reply; `jumble_sign_ins_total` by method;
- moderation: `jumble_reports_open`, listings with reports waiting;
- the outbox, the emails queued in the database and sent in the
  background: `jumble_outbox_emails` waiting, retrying and given up on, and
  `jumble_outbox_oldest_due_seconds`, how long the email due first has
  waited; `jumble_emails_total` handed to the mail server, sent or
  failed; and `jumble_storage_deletions_pending`;
- background work: `jumble_background_runs_total` by step and result, and
  `jumble_background_last_success_seconds` by step;
- browser notifications: `jumble_push_total` by result (sent, gone,
  failed, dropped);
- the chat channels: `jumble_chat_total` by channel (webhook, irc) and
  result, new posts announced (sent), tried again (failed) or not
  announced (dropped: off the board, too old, or refused by the webhook,
  which the log then says); and `jumble_irc_joined`, 1 while the IRC bot
  is in its channel;
- sizes: `jumble_database_bytes` of the database file and its
  write-ahead log, and `jumble_disk_bytes`, free and total, of the disk
  holding them;
- requests: `jumble_http_requests_total` by method, route and status, and
  their durations, `jumble_http_request_duration_seconds`, by route pattern
  (`/listings/{id}`), never by path;
- the JVM: memory, garbage collections, threads, processor time;
- backups: Litestream's own `litestream_*` series, and
  `jumble_litestream_up`, whether it answered.

[Grafana Cloud](https://grafana.com/products/cloud/)'s free plan reads it
with nothing installed on the server: add a **Metrics Endpoint**
connection with `https://<your address>/metrics` and the token as a bearer
token (without the word `Bearer`). A dashboard for it is in
[`deploy/grafana/jumble-dashboard.json`](../deploy/grafana/jumble-dashboard.json):
in Grafana, **Dashboards → New → Import**, upload the file, and choose the
Prometheus data source (Grafana Cloud's is `grafanacloud-<stack>-prom`);
the **Jumble** list at its top picks the scrape job.

Alerts worth setting (**Alerting → Alert rules → New alert rule**, the
query in code mode, `<job>` your scrape job's name; a contact point such
as your email first, under **Alerting → Contact points**):

| Alert | Query | Fires when, for 5 minutes |
|---|---|---|
| Email stuck | `max(jumble_outbox_oldest_due_seconds{job="<job>"})` | above 3600: the email due first has waited over an hour |
| Background step failing | `max by (step) (time() - jumble_background_last_success_seconds{job="<job>"})` | above 900: a step has not succeeded for 15 minutes (one failing once, the database busy, recovers on its own) |
| Backups down | `min(jumble_litestream_up{job="<job>"})` | below 1: Litestream does not answer |
| Backups failing | `sum(increase(litestream_sync_error_count{job="<job>"}[15m]))` | above 0: changes failed to reach the bucket |
| Metrics missing | `absent(jumble_members{job="<job>"})` | 1: Grafana gets nothing, the app or its token gone |
| IRC bot out | `max_over_time(jumble_irc_joined{job="<job>"}[15m])` | below 1: with JUMBLE_IRC_URL, the bot has not been in its channel for 15 minutes; the log says why (refused sign-in, kicked, banned, unreachable) |
| Disk filling up | `min(jumble_disk_bytes{job="<job>",state="free"} / ignoring(state) jumble_disk_bytes{job="<job>",state="total"})` | below 0.1: less than a tenth of the disk is free |

Jumble being down shows first in the uptime monitor; the backup heartbeat
stays, as it alerts from outside Grafana too.

## Backups

Litestream copies every change within a second, takes a snapshot daily and
keeps a week of them. The backup bucket's versioning keeps what Litestream
prunes, until its lifecycle rule expires it.

A backup that stops working shows only in the logs, and Jumble keeps
running. To be told, set `JUMBLE_BACKUP_HEARTBEAT_URL` to a heartbeat
monitor's address (healthchecks.io, Uptime Kuma's push monitors, Better
Stack and others): Litestream requests it every five minutes while the
database is backed up, and stops once a change fails to reach the bucket,
after at most one more request. Give the monitor a period of five minutes
and a grace of ten; it alerts when the requests stop.

To restore the latest state, stop Jumble, set its volume aside and start
it again: with no database on the volume, the container restores it from
the bucket.

```sh
image=$(sed -n 's/^Image=//p' /etc/containers/systemd/jumble.container)
sudo systemctl stop jumble
sudo podman run --rm -v jumble-data:/from:ro -v jumble-data-before-restore:/to \
  --entrypoint cp "$image" -a /from/. /to/
sudo podman volume rm jumble-data
sudo systemctl start jumble
```

Once the restored Jumble checks out, remove `jumble-data-before-restore`
(`sudo podman volume rm`). A restore that takes over five minutes is
stopped and the container exits with an error, rather than start on an
empty database; systemd then starts it again, which tries again within
the same limit. A database too large, or a link too slow, for that is
restored by hand, without a limit, before starting Jumble:

```sh
image=$(sed -n 's/^Image=//p' /etc/containers/systemd/jumble.container)
sudo systemctl stop jumble
sudo podman run --rm --env-file /etc/jumble/jumble.env -v jumble-data:/data \
  --entrypoint litestream "$image" restore -if-replica-exists /data/jumble.db
sudo systemctl start jumble
```

The same works on a new server: install it as in [deployment](deployment.md), with the same env
file, and start it.

Prove the backups restore before you need them, every month say. From your
machine:

```sh
INSTANCE=deploy/instances/community deploy/restore-drill.sh
```

On the server, it restores the latest backup into a scratch volume, with
the running image and its settings, and checks the copy: SQLite's
integrity check, the schema version, and the rows of the main tables
against the live database. Jumble keeps running and its volume is only
read; the scratch volume is removed however the drill ends. It passes when
the copy matches; rows written during the drill make it fail, so run it
again on a busy community. For a whole new server, try the restore above
on a second one with a copy of the env file, while the first is stopped.

To go back to an earlier state, within the week of history (an upgrade's
migration that went wrong, say), do as above but restore to that time
before starting: with a database on the volume, the container then keeps
it. The time is in UTC; `-dry-run` first shows what would be restored.
After an upgrade, deploy the previous image first (the one `deploy.sh`
printed), or starting would run the same migration again.

```sh
image=$(sed -n 's/^Image=//p' /etc/containers/systemd/jumble.container)
sudo systemctl stop jumble
# set the volume aside as above, then:
sudo podman volume rm jumble-data
sudo podman run --rm --env-file /etc/jumble/jumble.env -v jumble-data:/data \
  --entrypoint litestream "$image" restore -dry-run -timestamp 2026-11-08T09:00:00Z /data/jumble.db
sudo podman run --rm --env-file /etc/jumble/jumble.env -v jumble-data:/data \
  --entrypoint litestream "$image" restore -timestamp 2026-11-08T09:00:00Z /data/jumble.db
sudo systemctl start jumble
```

## Rotating keys and secrets

Create the new key or secret, put it in the instance's `jumble.env` on
your machine (`deploy/instances/<name>/jumble.env`) and deploy again, which
installs it and restarts Jumble; then revoke the old one. Deploying with
`HOST` alone, edit `/etc/jumble/jumble.env` on the server and run `sudo
systemctl restart jumble` instead: with `INSTANCE`, each deploy replaces
the server's copy with yours.

## Security checklist

- `/etc/jumble/jumble.env` owned by root, mode 600.
- With a proxy of your own, port 8080 reachable by it only (loopback, or
  the `FORWARD` chain).
- Both buckets private; each key limited to its bucket; the backup bucket
  versioned and its key unable to erase versions (`verify-keys.sh` passes).
- Sign-in limited to your community: allowed emails, an internal consent
  screen, or a provider that only knows your members.
- Images deployed by digest; the server's own updates applied
  automatically, and the server restarted when one needs it (with
  unattended-upgrades: `Unattended-Upgrade::Automatic-Reboot`), at an hour
  when a few minutes offline matter least.
- SSH with keys only: no password login, no root login
  (`PasswordAuthentication no`, `PermitRootLogin no`).
  [`deploy/server-setup.sh`](../deploy/server-setup.sh) sets up both, and
  checks the SSH settings in effect are these.
- The container runs as an unprivileged user, with all capabilities
  dropped and a read-only file system, writing only to its data volume and
  a `/tmp` in memory (`deploy/podman/jumble.container`); it listens on ports 80 and 443
  through a sysctl of its own network namespace
  (`net.ipv4.ip_unprivileged_port_start`), not a capability.
