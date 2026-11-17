-- The community's join link: whoever holds it may join, until moderators
-- renew it, turn it off, or it expires. One at most. Addresses that joined
-- by it are in the allowlist, which says the way each came in: added by a
-- moderator, joined by the link, or invited by a member. A sign-in started
-- from the link carries its token to where the account is made.

CREATE TABLE join_link (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  token TEXT NOT NULL,
  created_by INTEGER REFERENCES user (id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
) STRICT;

ALTER TABLE allowed_email ADD COLUMN way TEXT NOT NULL DEFAULT 'moderator' CHECK (way IN ('moderator', 'link', 'invitation'));
ALTER TABLE email_sign_in ADD COLUMN join_token TEXT;
ALTER TABLE login_attempt ADD COLUMN join_token TEXT;
