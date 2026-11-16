<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/jumble-web-pngs-hq/jumble-logo-white-640.png">
    <img src="assets/jumble-web-pngs-hq/jumble-logo-640.png" alt="Jumble" width="320" height="94">
  </picture>
</h1>

**A marketplace that belongs to your community.** The people of a company,
a school, a co-working space or a neighbourhood sell, give away, lend and
look for things, then sort it out face to face. No payments, no ratings,
no ads.

**[Try the demo →](https://demo.jumbl.ing)** · [Deploy your own](doc/quickstart.md) · [User guide](doc/user-guide.md)

Jumble is young and changing quickly.

## Highlights

- 🏷️ **Sell, give away, lend, or ask** for what you need, with up to five
  photos (HEIC from phones included, location data removed).
- 🔎 **Search and filter** the board; **save searches** for a daily email,
  **favorite** what you like.
- 💬 **Private messages** about each listing, with notifications on phone
  and desktop, and an email if left unread.
- 📣 **New posts announced in your chat**: Slack, Mattermost, Discord or IRC.
- 🔐 **Sign in your way**: a link by email, or Google Workspace, Keycloak
  and any OpenID Connect provider. Open to all, to some domains, or to a
  list.
- 🛡️ **Moderation built in**: reports, removals, who may join.
- 🎨 **Your community's look**: name, logo, 15 colour palettes, light and
  dark.
- 🌍 **11 languages**, each member choosing theirs.
- 🔒 **Private by design**: no tracking, no analytics; members download or
  delete everything about them.
- 📱 **Works everywhere**: phones (installable), desktops, and browsers
  without JavaScript.

## Small to run

- 🪶 **One process, one SQLite file**, for a 1 CPU, 2 GB server.
- 💾 **Continuous backups** to object storage with
  [Litestream](https://litestream.io), and a restore drill.
- 🔑 **HTTPS built in** (Let's Encrypt), or behind your own proxy.
- 🚀 **Up in minutes** on Exoscale, AWS or Scaleway with the scripts
  provided, or on any server with Podman.
- 📊 **Metrics, a Grafana dashboard and alerts**, ready to import.

It needs a server, a domain, S3-compatible storage and an SMTP account (or an
OpenID Connect provider for sign-in). The [quickstart](doc/quickstart.md)
picks them with you.

## Try it locally

With Docker, JDK 25+, the [Clojure CLI](https://clojure.org/guides/install_clojure)
and [libvips](https://www.libvips.org/install.html):

```sh
docker compose up -d     # sign-in, storage, email and certificate stand-ins
dev/s3-init.sh           # once: create the storage buckets
clojure -M:dev:run       # http://localhost:8080, as alice@example.com / password
clojure -M:dev:seed      # sample members and listings
```

More in [development](doc/development.md).

## Documentation

[User guide](doc/user-guide.md) ·
[Quickstart](doc/quickstart.md) ·
[Deployment](doc/deployment.md) ·
[Running it](doc/operations.md) ·
[Configuration](doc/configuration.md) ·
[Development](doc/development.md) ·
[Changelog](CHANGELOG.md)

## Built with

[Clojure](https://clojure.org), SQLite, [Datastar](https://data-star.dev)
and [Litestream](https://litestream.io).

## License

[AGPL 3.0](LICENSE), with a permission to combine it with Clojure and other
EPL libraries ([NOTICE](NOTICE)). Run it freely, for your organisation too;
if you offer a modified Jumble to others, share its source.

**For organisations**: hosting, support and a commercial license, at
[info@jumbl.ing](mailto:info@jumbl.ing).

**Contributing**: welcome, under the [contributor license agreement](CLA.md);
see [CONTRIBUTING.md](CONTRIBUTING.md).
