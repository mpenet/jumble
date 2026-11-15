-- Addresses of members the moderators removed: their accounts are deleted and
-- they never sign in again, whatever pattern lets their address in.
CREATE TABLE removed_email (
  email TEXT PRIMARY KEY COLLATE NOCASE,
  removed_by INTEGER REFERENCES user (id) ON DELETE SET NULL,
  removed_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;
