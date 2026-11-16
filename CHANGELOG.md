# Changelog

Jumble's releases, newest first. Versions are dates: `2026.11.0` is the
first release of November 2026, `2026.11.1` a fix to it. Each is an image,
`ghcr.io/mpenet/jumble:<version>`; `:latest` is the latest release, `:main`
the latest commit.

To upgrade, deploy the new version (see [running it](doc/operations.md)):
the database is migrated as it starts, from any earlier version. What needs
doing besides is under **Upgrade notes**.

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
