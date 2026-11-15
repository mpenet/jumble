-- Who may sign in, as added by moderators in the app: email patterns that
-- widen the configured ones (see account/membership.clj for the forms).
CREATE TABLE allowed_email (
  pattern TEXT PRIMARY KEY,
  added_by INTEGER REFERENCES user (id) ON DELETE SET NULL,
  added_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;
