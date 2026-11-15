# Configuration

Jumble reads its settings from environment variables, or from an optional
file. It checks everything at startup and refuses to start, saying which
setting is wrong, rather than run half configured. Categories and pickup
places are not settings: moderators manage them in the app (see the
[user guide](user-guide.md#for-moderators)).

## Environment variables

Required ones have no usable default: Jumble stops at startup when they are
missing, naming them. In `/etc/jumble/jumble.env`, values are taken as
they are, up to the end of the line: no quotes around them, and a `$` (in a
generated password, say) is a `$`.

### General

| Variable | Required | Default | |
|---|---|---|---|
| `JUMBLE_BASE_URL` | yes | | The address people use, e.g. `https://jumble.example.org`. Must be https (http is accepted for `localhost` only). Links in emails and sign-in redirects are built from it, never from the request. |
| `JUMBLE_APP_NAME` | | `Jumble` | Shown in the header (beside Jumble's tag mark when there is no logo), page titles and emails. |
| `JUMBLE_LOGO` | | none | The community's logo for the header: an SVG or PNG file, at most 200 KB, shown 2rem high and at most 12rem wide. With it, the footer says "Powered by Jumble". |
| `JUMBLE_PRIVACY_FILE` | | none | The community's own privacy text, a Markdown file of at most 200 KB, for the privacy page linked from every page. Without it, Jumble's default text says what is kept and why, who sees it, the cookies, how long, and how to export or delete one's data. |
| `JUMBLE_CONTACT` | | none | An email address for privacy questions, given on the default privacy page; without it, the page says to ask the moderators. |
| `JUMBLE_LOGO_DARK` | | none | A version of the logo for the dark theme, if the main one does not read on a dark background. |
| `JUMBLE_CURRENCY` | | `CHF` | ISO 4217 code of the prices, e.g. `EUR`, `USD`, `GBP`. Recorded in the database on first start; Jumble then refuses to start with another one, as stored prices would change meaning. |
| `JUMBLE_TIMEZONE` | | `UTC` | The time zone dates and times are shown in, such as `Europe/Zurich` or `America/New_York`. |
| `JUMBLE_LANGUAGE` | | `en` | The community's language, of pages and emails: `en`, `fr`, `de`, `it`, `es`, `pt` (European Portuguese), `sv`, `da`, `nb` (Norwegian Bokmål), `fi` or `nl`. Members may choose another in their settings; this one is theirs until they do, and that of invitations. None of the translations has had a native speaker's review yet; corrections are welcome (see [translations](development.md#translations)). Category and place names are data: moderators name them in the community's language. |
| `JUMBLE_MODERATORS` | | none | Email addresses of the people who handle reports, comma separated. |
| `JUMBLE_CONFIG_FILE` | | none | Path of the [settings file](#settings-file). |
| `JUMBLE_HOST`, `JUMBLE_PORT` | | `127.0.0.1`, `8080` | Where the app listens over plain HTTP, the port from 1 to 65535; with HTTPS on, this port answers Let's Encrypt and redirects to https. The container image sets `0.0.0.0` and `8080`; `deploy/podman/https.conf` sets 80. Its health check follows `JUMBLE_PORT`. |
| `JUMBLE_HTTPS_PORT` | | none | HTTPS served by Jumble on this port, TCP (HTTP/2) and UDP (HTTP/3), with a certificate for `JUMBLE_BASE_URL`'s host, which must then be https. Off without it. `deploy/podman/https.conf` sets 443: HTTP/3 is offered on the port the app listens on, which must be the one people reach. |
| `JUMBLE_ACME_EMAIL` | | none | With HTTPS on, an address the CA may write to about the certificate. |
| `JUMBLE_ACME_DIRECTORY` | | Let's Encrypt | With HTTPS on, the ACME directory URL of the CA the certificate comes from. Its staging one, `https://acme-staging-v02.api.letsencrypt.org/directory`, issues untrusted certificates under far higher limits, for trials. |
| `JUMBLE_CERTIFICATES_DIR` | | `certificates` | With HTTPS on, where the certificate and the CA account are kept: keep it across restarts. `/data/certificates` in `deploy/podman/https.conf`. |
| `JUMBLE_DB_PATH` | | `jumble.db` | The SQLite database. `/data/jumble.db` in the container image. |

### Sign-in

| Variable | Required | Default | |
|---|---|---|---|
| `JUMBLE_SIGN_IN` | yes | | How members sign in: `oidc`, with an OpenID Connect provider, or `email`, with a link sent by email. One per instance. See [signing in](#signing-in). |
| `JUMBLE_ALLOWED_EMAILS` | yes | | Who may sign in: `*`, or email patterns, comma separated. See [who may sign in](#who-may-sign-in). |
| `JUMBLE_OIDC_ISSUER` | with `oidc` | | The OpenID Connect issuer URL, e.g. `https://accounts.google.com`. Must be https. |
| `JUMBLE_OIDC_CLIENT_ID` | | `jumble` | The client registered with the provider. |
| `JUMBLE_OIDC_CLIENT_SECRET` | with `oidc` | | Its secret. |
| `JUMBLE_OIDC_REQUIRE_HOSTED_DOMAIN` | with Google | `false` | `true`: the account must also be managed by the organisation of its address (see [Google](#google-workspace)). |

### Photo storage

| Variable | Required | |
|---|---|---|
| `JUMBLE_S3_ENDPOINT` | yes | The storage service's URL, scheme and host only, e.g. `https://s3.example.com`. Must be https. |
| `JUMBLE_S3_REGION` | yes | Its region, as it expects in signatures. |
| `JUMBLE_S3_BUCKET` | yes | The photo bucket. Keep it private: Jumble hands browsers short-lived signed links. |
| `JUMBLE_S3_ACCESS_KEY`, `JUMBLE_S3_SECRET_KEY` | yes | A key limited to that bucket. |

### Backups

Used by the container image, which runs Litestream next to the app.

| Variable | Required | Default | |
|---|---|---|---|
| `JUMBLE_BACKUP_BUCKET` | yes | | The backup bucket, on the same endpoint and region as the photos. |
| `JUMBLE_BACKUP_PATH` | | `jumble` | Where in the bucket. |
| `JUMBLE_BACKUP_ACCESS_KEY`, `JUMBLE_BACKUP_SECRET_KEY` | yes | | A key limited to the backup bucket. |
| `JUMBLE_BACKUP_HEARTBEAT_URL` | | | A heartbeat monitor's address, requested every five minutes while backups work: its monitor alerts when they stop (see [Backups](operations.md#backups)). |

### Email

| Variable | Required | Default | |
|---|---|---|---|
| `JUMBLE_SMTP_HOST` | yes | | The SMTP server. |
| `JUMBLE_SMTP_PORT` | | `587` | Jumble speaks SMTP with STARTTLS; implicit TLS (port 465) is not supported. |
| `JUMBLE_SMTP_STARTTLS` | | `true` | `false` only for a server on the same machine, or with `JUMBLE_ALLOW_INSECURE_URLS`. |
| `JUMBLE_SMTP_USERNAME`, `JUMBLE_SMTP_PASSWORD` | | none | The login, both or neither. |
| `JUMBLE_MAIL_FROM` | yes | | The sender, with an optional name: `Jumble <jumble@example.org>`. Replies go there too. |

### Hosting and operations

Running an instance: behind a proxy of your own, monitored, hosted for
others, or capped.

| Variable | Default | |
|---|---|---|
| `JUMBLE_LISTING_REMINDER_DAYS` | `60` | Days a listing goes without news (posting, editing, a status change) before its owner is emailed to ask whether it is still available; unanswered for 15 more days, it leaves the board, until its owner puts it back. `0`: owners are never asked and listings never expire. Listings to lend are never asked about. |
| `JUMBLE_MEMBER_LIMIT` | none | The most members the community may have, counting every account not deleted. Past it, someone new who is allowed to sign in is told the community is full; members already there are not affected, and the moderators (`JUMBLE_MODERATORS`) can always join. Deleting accounts makes room. |
| `JUMBLE_BEHIND_PROXY` | `false` | Behind a reverse proxy of your own: each client's address is the last in the `X-Forwarded-For` header the proxy adds, so only the proxy may reach Jumble. Set by `MODE=own-proxy` deploys (see [with a proxy of your own](deployment-reference.md#with-a-proxy-of-your-own)); not with `JUMBLE_HTTPS_PORT`. |
| `JUMBLE_METRICS_TOKEN` | none | Turns on `GET /metrics` for requests carrying `Authorization: Bearer <token>`, in Prometheus' text format: members and listings, the outbox and background work, requests, the JVM, activity, and Litestream's own (see [metrics](operations.md#metrics)). At least 32 characters (`openssl rand -hex 32`). Without it, `/metrics` is not found. |
| `JUMBLE_LITESTREAM_METRICS_URL` | set by the image | Where Litestream serves its metrics inside the container, for `/metrics` to add them. The image sets it; leave it unless you run Jumble outside it. |
| `JUMBLE_MODERATOR_NOTICE_FILE` | none | A Markdown file shown at the top of the moderators' pages while it exists, read each time: change or remove it without restarting. |
| `JUMBLE_SOURCE_URL` | Jumble's repository | Where the footer's "Source code" link leads. Jumble is under the AGPL: running a modified version for others, point this at your version's source, which you must offer them. |
| `JUMBLE_HOSTED_BY`, `JUMBLE_HOSTED_BY_URL` | none | Who hosts the instance, named in the footer ("Hosted by …"), linked to the https address when given. |

### Development only

| Variable | |
|---|---|
| `JUMBLE_ALLOW_INSECURE_URLS` | `true` accepts http for the issuer and storage, and SMTP without STARTTLS, beyond `localhost`: for containers talking to each other in development. Never in production. |

## Settings file

`JUMBLE_CONFIG_FILE` names an [edn](https://github.com/edn-format/edn) file
merged over the built-in settings: maps are merged key by key, lists are
replaced as a whole. It holds what has no environment variable, and long
lists that read better one per line:

```clojure
{;; How long a sign-in lasts without a visit, in days, from 1 to 30 (at
 ;; most 30 days in all, however often renewed).
 :auth {:session-ttl-days 7
        :allowed-emails ["*@example.org" "alice@gmail.com"]}}
```

- Any setting can be set here, as in the built-in
  [`resources/config.edn`](../resources/config.edn); environment variables
  are the usual way. A setting in the file wins over its environment
  variable.

## Signing in

Each instance uses one way of signing in, set by `JUMBLE_SIGN_IN`.

### By email (`email`)

Members enter their address and receive a link that signs them in. Nothing
else to set up: Jumble already sends email. Right for communities without a
sign-in service of their own: a neighbourhood, a club, a co-working space.

- A link works once, within 15 minutes, and opens a page asking to confirm
  (mail scanners that follow links cannot use it up).
- Everyone asking is told the same, whether or not their address may sign
  in: the page does not reveal who belongs.
- At most 3 links per address every 15 minutes, and 100 in all to addresses
  without an account yet: someone flooding the form with made-up
  addresses cannot stop members from signing in.
- When Jumble serves HTTPS itself, at most 5 links every 15 minutes asked
  for by one client, whatever the addresses (an IPv6 client counts by its
  /64). Behind a proxy of your own every request comes from the proxy, so
  this limit does not apply; the proxy can limit requests to `POST /login`.
- Whether an address is allowed is checked again when the link is used.
- Members start with their address as their name, and can choose another
  in Settings.

### With an OpenID Connect provider (`oidc`)

Members sign in with the account they already have, with the provider your
organisation uses (authorization code flow with PKCE). Register Jumble with
the provider as a web application:

- **Redirect URI**: `<JUMBLE_BASE_URL>/auth/callback`.
- **Scopes**: `openid email profile`.
- **Client authentication**: client secret sent with HTTP Basic
  (`client_secret_basic`).

The provider's ID tokens must carry the person's `email` with
`email_verified: true`: Jumble refuses unverified addresses. Most providers
send both; some do not by default (Microsoft Entra ID among them). For
those, put a broker that does, such as Keycloak or Dex, in front.

Members are identified by the provider and their account there, not by
email: changing provider, or switching to email sign-in, means members
start afresh.

#### Google Workspace

For a community on Google Workspace:

1. In the Google Cloud console of the Workspace, configure the OAuth
   consent screen as **Internal**: only the Workspace's accounts can then
   sign in.
2. Create an OAuth client of type **Web application** with the redirect URI
   above.
3. Configure:

   ```sh
   JUMBLE_SIGN_IN=oidc
   JUMBLE_OIDC_ISSUER=https://accounts.google.com
   JUMBLE_OIDC_CLIENT_ID=<client id>
   JUMBLE_OIDC_CLIENT_SECRET=<client secret>
   JUMBLE_ALLOWED_EMAILS=*@example.org
   JUMBLE_OIDC_REQUIRE_HOSTED_DOMAIN=true
   ```

Why the last line: anyone can create a personal Google account with a work
address, verify it, and keep it after leaving. Google marks accounts a
Workspace manages with the `hd` claim, its domain; Jumble then requires it to
be the address's own domain when a domain pattern let the address in. An
address listed by itself (a guest's `someone@gmail.com`, say) needs no `hd`:
it was named, not inferred from its domain. With Google and anything but
`*`, Jumble refuses to start without the setting.

#### Other providers

Keycloak, Okta, Dex and the like work the same way: create a client for a
web application with the redirect URI above, then set the issuer (the URL
that serves `/.well-known/openid-configuration`), client id and secret.
Leave `JUMBLE_OIDC_REQUIRE_HOSTED_DOMAIN` unset: only Google sends the claim
it checks.

### Who may sign in

`JUMBLE_ALLOWED_EMAILS` applies to both ways of signing in. Patterns are
comma separated, case is ignored, and an address needs to match one:

| Openness | Example |
|---|---|
| Fully open | `*` |
| A domain | `*@example.org` |
| A domain's subdomains | `*@*.example.org` (add `*@example.org` for the domain itself) |
| Single addresses | `alice@example.org, bob@gmail.com` |
| Mixed | `*@example.org, guest@gmail.com` |

`*` is the only wildcard, and only in those places. A long list of
addresses can go in the [settings file](#settings-file) instead:

```clojure
{:auth {:allowed-emails ["*@example.org" "alice@gmail.com" "bob@proton.me"]}}
```

Changes take effect when Jumble restarts. Moderators can also let more
people in from Jumble itself, without a restart (see the
[user guide](user-guide.md#for-moderators)): what they add widens the
configured patterns, never narrows them. Whoever no longer matches, after a
moderator removes an entry or the configuration changes, is signed out at
their next click.

Fully open means anyone: with email sign-in, anyone with an email address;
with a provider, anyone that provider signs in. That suits a provider that
only knows your members (a Keycloak realm you invite people to). It does not
suit Google without an internal consent screen, where anyone with a Google
account could join.

## Email providers

Jumble sends plain text email: sign-in links (with email sign-in),
invitations sent by moderators, notifications of unread messages, the news
of saved searches, the weekly email of what is new, questions to owners of
listings without news, alerts to moderators about reports, and notices to
owners whose listing was removed. Each goes in its recipient's language.

- **Gmail or Google Workspace**: host `smtp.gmail.com`, port 587, the
  account as username and an [app password](https://support.google.com/accounts/answer/185833)
  (the account needs 2-step verification). The sender must be that account
  or one of its aliases. Google limits how much an account sends per day,
  which suits a community of hundreds.
- **Transactional email services** (and most hosting providers): use their
  SMTP host, port 587 and SMTP credentials, with a sender on a domain you
  have set up with them.
- **Your own server**: any SMTP server accepting STARTTLS on port 587.

## Object storage

Any S3-compatible service works if it supports AWS Signature Version 4 and
path-style URLs (`https://endpoint/bucket/key`). Jumble is tested against
[Garage](https://garagehq.deuxfleurs.fr); Litestream, which does the
backups, supports most S3-compatible services.

Create two private buckets in the same region, and one key per bucket:

- **Photos**: the key needs to read, write and delete objects in the photo
  bucket, nothing else. Browsers fetch photos through links Jumble signs
  with it, each valid one to two days and the same all day, so browsers
  cache the photos.
- **Backups**: turn on versioning for the bucket. The key needs to read,
  write, list and delete objects in it, but not to delete object versions
  or change versioning: then whatever the key does, earlier versions of the
  backup stay recoverable. How to express this depends on the service's
  access policies; [`deploy/verify-keys.sh`](../deploy/verify-keys.sh)
  checks the result.

Photos are not part of the backups: rely on the storage service's
durability, or turn on versioning for the photo bucket too.
