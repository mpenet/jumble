-- Invitations members sent, by email: to whom and when, for the limit on
-- how many a day and for each member to see theirs.

CREATE TABLE invitation (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  email TEXT NOT NULL,
  sent_at INTEGER NOT NULL
) STRICT;

CREATE INDEX invitation_user_sent ON invitation (user_id, sent_at);
