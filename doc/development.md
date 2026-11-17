# Development

## Setup

You need JDK 25 or later, the [Clojure CLI](https://clojure.org/guides/install_clojure),
[libvips](https://www.libvips.org/install.html)'s `vips` command (`brew install
vips`, `apt install libvips-tools`) and Docker. Then:

```sh
docker compose up -d       # Dex (sign-in), Garage (storage), Mailpit (email), Pebble (certificates)
dev/s3-init.sh             # once: buckets and key in Garage
clojure -M:dev:run         # http://localhost:8080
```

The development services:

- **Dex** at http://127.0.0.1:5556/dex, with two users, both with password
  `password`: `alice@example.com` (allowed, and a moderator in development) and
  `mallory@other.example.org` (refused: not in the allowed emails).
- **Garage** at http://127.0.0.1:3900, an S3-compatible store with a
  `jumble` bucket for photos and `jumble-backup` for backup checks.
- **Mailpit**, catching every email Jumble sends, at http://localhost:8025.
- **Pebble**, Let's Encrypt's test CA, at https://localhost:14000/dir: it
  issues certificates without checking challenges, for the integration
  tests and for trying HTTPS as production serves it.

`clojure -M:dev:seed` adds sample members and listings through the same
model functions the app uses, plus two conversations for alice if she has
signed in once; `--reset` removes all listings first. It refuses to run
outside the development profile.

To try email sign-in instead, start with `JUMBLE_SIGN_IN=email`; the links
arrive in Mailpit.

To try HTTPS served by Jumble, with a certificate from Pebble, trust
Pebble's own certificate for the JVM, and give the app a name of its own
(with a dot: Java sends no server name for `localhost`, and the
certificate is picked by it):

```sh
keytool -importcert -noprompt -file test-resources/pebble.minica.pem \
  -keystore target/pebble.p12 -storetype PKCS12 -storepass changeit
JUMBLE_BASE_URL=https://jumble.test JUMBLE_HTTPS_PORT=8443 \
  JUMBLE_ACME_DIRECTORY=https://localhost:14000/dir JUMBLE_CERTIFICATES_DIR=target/certificates \
  clojure -J-Djavax.net.ssl.trustStore=target/pebble.p12 -J-Djavax.net.ssl.trustStorePassword=changeit -M:dev:run
curl --resolve jumble.test:8443:127.0.0.1 -k https://jumble.test:8443/healthz
```

Port 8080 then answers the CA's challenges and redirects to HTTPS. `-k`, as
Pebble signs with a root of its own, made anew each time it starts
(https://localhost:15000/roots/0).

For a REPL: `clojure -M:dev:nrepl`.

## Profiles

Settings come from [`resources/config.edn`](../resources/config.edn). The
`:dev` and `:test` aliases set `-Djumble.profile=dev`, whose defaults point
at the development services; without it the profile is production, where
required settings must come from the environment.

## Tests and checks

Before pushing, `dev/check.sh` runs what CI's test job runs: the unit tests,
lint, and the deploy scripts and units checked with Ubuntu 24.04's
shellcheck and Podman (in a container of that system, with Docker or
Podman), the versions CI and servers have. Each part on its own:

```sh
clojure -M:test                       # unit tests
clojure -M:test:integration           # against Garage, Mailpit and Pebble (docker compose up)
clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.10.26"}}}' \
  -M -m clj-kondo.main --lint src test dev
docker build -t jumble:dev . && dev/backup-restore-check.sh
```

`dev/backup-restore-check.sh` runs the production image against Garage:
it writes data, deletes the container's volume, restarts, and checks that
the restored database is the real one.

The [workflow](../.github/workflows/image.yml) runs the unit tests and the
linter on every change, then the integration tests against the
development services and the backup check on the image; on `main`, a job
of its own pushes that image to the registry.

## Releasing

Versions are dates, `2026.11.0`, then `2026.11.1` for a fix to it (see
[the changelog](../CHANGELOG.md)). To release:

1. Add the version's section to `CHANGELOG.md`, `## 2026.11.0 (date)`:
   what is new, what is fixed, and **Upgrade notes**, anything operators
   must do besides deploying it (a setting renamed, a long migration).
2. Commit it to `main`, and wait for the workflow to pass.
3. Tag that commit and push the tag:

   ```sh
   git tag -a v2026.11.0 -m "Jumble 2026.11.0"
   git push origin v2026.11.0
   ```

The workflow tests and builds the tagged commit, pushes its image as
`:2026.11.0`, `:2026.11` and `:latest`, and publishes the GitHub release
with the changelog's section. A tag that is not `vYYYY.MM.N`, or a version
without its section, fails before anything is published.

## How the code is organised

```
src/s_exp/jumble.clj              entry point: system, startup, signals
src/s_exp/jumble/                 shared pieces: config, errors, OIDC, HTTP
                                  client, images, money, tokens, metrics
src/s_exp/jumble/store/           database, migrations, S3, photo storage
src/s_exp/jumble/web/             server, routes and middleware, auth,
                                  security headers, HTTPS, params, responses
src/s_exp/jumble/view/            layout, translations, markdown, dates,
                                  icons, avatars, QR codes
src/s_exp/jumble/page/            pages outside the contexts: privacy, health,
                                  metrics, assets, manifest, theme, palette,
                                  branding
src/s_exp/jumble/email/           the outbox, its delivery, SMTP
src/s_exp/jumble/push/            browser notifications
src/s_exp/jumble/<context>/       one directory per part of the product:
                                  account, home, listing, message, moderation
resources/migrations/             numbered SQL migrations
resources/public/                 CSS, vendored Datastar, logo and icons
assets/                           brand kit: logo, stacked logo and mark, light
                                  and white, at several sizes
test/s_exp/jumble/                mirrors src
dev/                              development services' config, seed, checks
deploy/                           production units (Podman), env template, scripts
```

Each context is split three ways:

- **model**: data in, data out. Owns its SQL and the rules (who may do
  what, which states follow which). Takes the database (and storage) as
  arguments; knows nothing of HTTP or HTML.
- **view**: pure functions from data to HTML ([chassis](https://github.com/onionpancakes/chassis)),
  full pages and the fragments Datastar swaps in.
- **controller**: Ring handlers reading the request, calling the model,
  rendering the view; the only part aware of HTTP. Each exposes a `routes`
  map, merged in `web/routes.clj`.

## Conventions

- **Tests first.** Every feature and fix starts with a failing test.
- **Writes go through `db/with-write-tx`.** SQLite has one writer; this
  queues writers on a JVM lock (with a timeout answered as 503) instead of
  in SQLite's busy handler.
- **Migrations are append-only**: add a numbered file to
  `resources/migrations/` and list it in `store/migrate.clj`; never edit one that
  has shipped. They run at startup in a transaction, foreign keys off and
  checked afterwards, so table rebuilds are safe. Add a test for any
  migration that moves data.
- **No reflection**: every namespace sets `*warn-on-reflection*`.
- **Work without JavaScript**: pages are plain links and forms; Datastar
  enhances them.
- **Nothing personal in logs**: email domains, ids; never addresses or
  message contents.

## Translations

Every text people read goes through `i18n/t` (or `i18n/tn` for plurals),
with the English text as the key and named placeholders:

```clojure
(i18n/t "Posted by {name}" {:name owner-name})
(i18n/tn "{n} listing" "{n} listings" n)
```

The translations are gettext catalogs in `resources/i18n`: `jumble.pot`,
the template, and one `.po` file per language, which any gettext tool
(Poedit, Weblate, Lokalize…) can edit. After changing text in the code,
update them:

```sh
clojure -M:dev -m i18n-catalogs
```

New text comes in untranslated; the tests fail until every language has it,
with the same placeholders. A missing translation shows the English.

To add a language: add its tag and name to `i18n/language-names` and its
plural rule to `i18n/plural-rules` and `dev/i18n_catalogs.clj`, run the
command above, and translate the new `.po` file.

None of the translations has had a native speaker's review yet: if you
read one daily, corrections to its `.po` file are most welcome. French uses
*vous*; the others the informal form of address: German, Swedish, Danish
and Norwegian (Bokmål) *du*, Italian and Portuguese (European) *tu*,
Spanish *tú*, Finnish *sinä*, Dutch *je*.
