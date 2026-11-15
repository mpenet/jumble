# Quickstart

What to use to run a Jumble, and the steps to have one up in about half an
hour. [Deployment](deployment.md) has the other ways and every detail.

## What to use

A Jumble needs a small server, object storage, a way to send email and,
optionally, a domain and some monitoring. Each line below names one choice
that works well, then others. The server and storage are paid; free plans
cover email, DNS and monitoring.

| Need | A good start | Others |
|---|---|---|
| **Server**: 1 CPU, 2 GB of memory, Ubuntu or Debian | [Exoscale](https://www.exoscale.com) `standard.small`, created with its storage by `deploy/provision.sh exoscale` (well tested; Swiss) | Any provider's small VM, prepared by `deploy/bootstrap.sh`: Hetzner, Scaleway, AWS, OVH, a machine of your own |
| **Object storage**: two buckets, one versioned | The server's provider's: Exoscale SOS comes with the script | AWS S3, Scaleway, Hetzner, Backblaze B2, MinIO: any S3-compatible storage keeping object versions |
| **Email**: sign-in links, messages, reminders, the weekly email | A transactional email service with SMTP: [Resend](https://resend.com), [Brevo](https://www.brevo.com), [Postmark](https://postmarkapp.com), Amazon SES, Scaleway Transactional Email | Gmail or Google Workspace for a small community (see [email providers](configuration.md#email-providers)) |
| **Sign-in** | Links sent by email: nothing to set up beyond email | Your organisation's accounts: Google Workspace, Keycloak or another OpenID Connect provider (see [signing in](configuration.md#signing-in)) |
| **Address** | A domain or subdomain of yours, its DNS anywhere ([Cloudflare](https://www.cloudflare.com)'s is free) | None: the scripts give the server an address under [sslip.io](https://sslip.io), a free service giving any IP address a name, with a certificate all the same |
| **Monitoring** | [UptimeRobot](https://uptimerobot.com) on `/healthz`, and [healthchecks.io](https://healthchecks.io) for the backup heartbeat | [Grafana Cloud](https://grafana.com/products/cloud/) for the [metrics](operations.md#metrics), with a ready dashboard |

For the email service, set up your domain with it (it gives the DNS records
to add, so that mail from it is not taken for spam), then create SMTP
credentials and choose a sender, such as `jumble@your-domain`.

The demo, [demo.jumbl.ing](https://demo.jumbl.ing), runs on these first
choices: Exoscale in Geneva, its object storage, Resend, Cloudflare's DNS,
UptimeRobot, healthchecks.io and Grafana Cloud.

## Steps

On your machine, with this repository, Docker or Podman, and the
[`exo` CLI](https://community.exoscale.com/tools/command-line-interface/)
with an SSH key registered with Exoscale (see
[cloud providers](cloud-providers.md#exoscale) for its configuration):

1. **Create the server and storage:**

   ```sh
   SSH_KEY=<key name> DOMAIN=market.example.org deploy/provision.sh exoscale community
   ```

   It prints the server's address. Leave `DOMAIN` out to use one under
   sslip.io.

2. **Point the domain at it**: an A record for `market.example.org` with
   that address.

3. **Fill in the settings** in `deploy/instances/community/jumble.env`; the
   storage, its keys and the address are there already:

   ```sh
   JUMBLE_APP_NAME=Our market
   JUMBLE_SIGN_IN=email
   JUMBLE_ALLOWED_EMAILS=*@example.org
   JUMBLE_MODERATORS=you@example.org
   JUMBLE_SMTP_HOST=smtp.your-email-service.com
   JUMBLE_SMTP_USERNAME=…
   JUMBLE_SMTP_PASSWORD=…
   JUMBLE_MAIL_FROM=Our market <jumble@example.org>
   JUMBLE_CURRENCY=CHF
   ```

   The currency cannot change later. [Configuration](configuration.md) has
   every other setting: language, logo, time zone, who may sign in.

4. **Check the storage keys, and deploy:**

   ```sh
   deploy/verify-keys.sh deploy/instances/community/jumble.env
   INSTANCE=deploy/instances/community deploy/deploy.sh
   ```

5. **Open the address and sign in** with your address: you are its first
   moderator. Post a listing with a photo, and check that a message left
   unread for five minutes arrives by email.

6. **Watch it**: an uptime monitor on `https://market.example.org/healthz`,
   and `JUMBLE_BACKUP_HEARTBEAT_URL` set to a heartbeat monitor's address,
   deployed again. Then [running it](operations.md): upgrades, backups and
   restoring.

Keep a copy of `deploy/instances/community/` somewhere safe, such as a
password manager: with the backups, it is all a new server needs.
