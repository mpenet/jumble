-- Invitations members sent, by email: to whom and when, for the limits on
-- how many a day and for each member to see theirs, kept 90 days.

CREATE TABLE invitation (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  email TEXT NOT NULL,
  sent_at INTEGER NOT NULL
) STRICT;

CREATE INDEX invitation_user_sent ON invitation (user_id, sent_at);
CREATE INDEX invitation_sent ON invitation (sent_at);
