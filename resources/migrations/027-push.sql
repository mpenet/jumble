-- Browser notifications: the devices a member turned them on for, while
-- signed in there (gone with the session), with the keys their browser
-- gave, and the notifications waiting to be sent to them, one per new
-- message and device.
CREATE TABLE push_subscription (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id),
  session_hash BLOB NOT NULL REFERENCES session (token_hash) ON DELETE CASCADE,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at INTEGER NOT NULL
) STRICT;

CREATE INDEX push_subscription_user ON push_subscription (user_id);
CREATE INDEX push_subscription_session ON push_subscription (session_hash);

CREATE TABLE push_delivery (
  id INTEGER PRIMARY KEY,
  subscription_id INTEGER NOT NULL REFERENCES push_subscription (id) ON DELETE CASCADE,
  message_id INTEGER NOT NULL REFERENCES message (id),
  due_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX push_delivery_due_at ON push_delivery (due_at);
