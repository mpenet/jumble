# Changelog

Jumble's releases, newest first. Versions are dates: `2026.11.0` is the
first release of November 2026, `2026.11.1` a fix to it. Each is an image,
`ghcr.io/mpenet/jumble:<version>`; `:latest` is the latest release, `:main`
the latest commit.

To upgrade, deploy the new version (see [running it](doc/operations.md)):
the database is migrated as it starts, from any earlier version. What needs
doing besides is under **Upgrade notes**.

## Unreleased

- An owner, named by the configuration (`JUMBLE_OWNER`), sets up the
  community in Jumble: who may sign in, the moderators, the appearance and
  the poster. Moderators, appointed by the owner on **Moderators**, handle
  reports, remove listings and members, and keep the categories and pickup
  places. The owner can always sign in.
- Who may sign in is the owner's to say, in Jumble: **Let anyone sign in**,
  or the list of addresses and domains kept there, which stays while anyone
  may. A new community lets its owner in alone.
- **Remove a member** is on the **Reports** page.
- With Google, the organisation managing an account is checked whenever a
  domain on the list let its address in, without a setting; where anyone
  may sign in, any Google account can.

### Upgrade notes

`JUMBLE_MODERATORS`, `JUMBLE_ALLOWED_EMAILS` and
`JUMBLE_OIDC_REQUIRE_HOSTED_DOMAIN` are gone, and Jumble refuses to start
with `:moderators` or `:allowed-emails` in a settings file. `JUMBLE_OWNER`
is required. After upgrading, only the owner can sign in until they choose
who else may, under **Who may sign in**, and appoint the moderators.

## 2026.11.3 (2026-11-18)

- Mitigates Java4Shell ([CVE-2026-114938](https://calif.io/java4shell/)),
  a bug in the JVM's Base64 code on x86-64 processors with AVX-512: the
  image runs Java with `-XX:-UseBASE64Intrinsics`. ARM64 is not affected.

### Upgrade notes

Upgrade as soon as you can if your server has an x86-64 processor. Nothing
else changes. `JAVA_OPTS`, if set, still comes after this flag.

## 2026.11.2 (2026-11-17)

- A join link: moderators make one on **Who may sign in**, working for good
  or until a date, and renew or turn it off. Whoever opens it joins, by
  email or the sign-in provider, without being on the list. The poster's
  code can open it.
- Members' invitations: when moderators let them, members invite people
  they know by email, five a day each and `JUMBLE_INVITATIONS_PER_DAY`
  (100 by default) in all. Invitations are kept 90 days.
- The list of who may sign in says how each address came in: added by a
  moderator, joined by the link, or invited by a member.
- Addresses with `*` are refused at sign-in: on the list, `*` lets in a
  whole domain.
- With a sign-in provider, the members page tells moderators that whoever
  joins by the link or an invitation needs an account there.

### Upgrade notes

The database is migrated as Jumble starts (migrations 031 to 033). Nothing
changes until moderators make a join link or let members invite people.

## 2026.11.1 (2026-11-16)

- Logs can be sent to [Axiom](https://axiom.co): with an `axiom.env` in the
  instance (dataset, ingest token, and the organisation's region),
  `deploy.sh` runs Fluent Bit next to Jumble, reading the app's lines from
  the journal, in at most 64 MB of memory. See
  [running it](doc/operations.md#logs-in-axiom).

### Upgrade notes

None: logs stay on the server unless `axiom.env` is added.

## 2026.11.0 (2026-11-16)

The first release.

- Listings to sell, give away, lend, or ask for, with up to five photos
  (HEIC included, location data removed), categories and pickup places.
- The board: search, filters, saved searches with a daily email,
  favorites, and a weekly email of what is new.
- Private messages about each listing, with browser notifications and an
  email when left unread.
- New posts announced on a chat channel: Slack, Mattermost, Discord or IRC.
- Sign-in by email link or OpenID Connect, open to all, to some domains,
  or to a list; moderation: reports, removals, who may join.
- The community's name, logo and palette (fifteen, light and dark), in
  eleven languages.
- Members download or delete everything about them.
- One process and one SQLite file, backed up continuously with
  Litestream; HTTPS built in; metrics, a Grafana dashboard and alerts;
  provisioning scripts for Exoscale, AWS and Scaleway.

### Upgrade notes

None: the first release.
