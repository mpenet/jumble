-- The community's join link: whoever holds it may join, until moderators
-- renew it, turn it off, or it expires. One at most. Addresses that joined
-- by it are in the allowlist, marked; a sign-in started from it carries
-- its token to where the account is made.

CREATE TABLE join_link (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  token TEXT NOT NULL,
  created_by INTEGER REFERENCES user (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
) STRICT;

ALTER TABLE allowed_email ADD COLUMN joined_by_link INTEGER NOT NULL DEFAULT 0 CHECK (joined_by_link IN (0, 1));
ALTER TABLE email_sign_in ADD COLUMN join_token TEXT;
ALTER TABLE login_attempt ADD COLUMN join_token TEXT;
