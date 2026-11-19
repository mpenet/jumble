-- The moderators the owner appointed in the app, by address: they handle
-- reports, listings, members, categories and places, and can always sign in.
CREATE TABLE moderator (
  email TEXT PRIMARY KEY,
  added_by INTEGER REFERENCES user (id) ON DELETE SET NULL,
  added_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;
