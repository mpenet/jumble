# Changelog

Jumble's releases, newest first. Versions are dates: `2026.11.0` is the
first release of November 2026, `2026.11.1` a fix to it. Each is an image,
`ghcr.io/mpenet/jumble:<version>`; `:latest` is the latest release, `:main`
the latest commit.

To upgrade, deploy the new version (see [running it](doc/operations.md)):
the database is migrated as it starts, from any earlier version. What needs
doing besides is under **Upgrade notes**.

## Unreleased

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
