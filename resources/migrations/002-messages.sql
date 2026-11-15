-- Messages between two people about a post. A thread keeps the post's
-- title, so it still reads once the post is removed (listing_id is then
-- NULL). Participants record the last message they have read, which drives
-- unread counts.

CREATE TABLE thread (
  id INTEGER PRIMARY KEY,
  subject TEXT NOT NULL CHECK (length(subject) BETWEEN 1 AND 120),
  listing_id INTEGER REFERENCES listing (id) ON DELETE SET NULL,
  post_title TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_message_at INTEGER NOT NULL
) STRICT;

CREATE INDEX thread_listing_id ON thread (listing_id);

CREATE TABLE thread_participant (
  thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES user (id),
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (thread_id, user_id)
) STRICT, WITHOUT ROWID;

CREATE INDEX thread_participant_user_id ON thread_participant (user_id);

CREATE TABLE message (
  id INTEGER PRIMARY KEY,
  thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
  sender_id INTEGER NOT NULL REFERENCES user (id),
  body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
  created_at INTEGER NOT NULL
) STRICT;

CREATE INDEX message_thread_id ON message (thread_id, id);
