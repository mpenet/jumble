-- New posts waiting to be announced to the community's chat channel: due a
-- few minutes after posting, tried again later when the channel fails.

CREATE TABLE chat_announcement (
  id INTEGER PRIMARY KEY,
  listing_id INTEGER NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
  due_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX chat_announcement_due_at ON chat_announcement (due_at);
CREATE INDEX chat_announcement_listing ON chat_announcement (listing_id);
