-- Email notifications of new messages.

ALTER TABLE user ADD COLUMN notify_messages INTEGER NOT NULL DEFAULT 1 CHECK (notify_messages IN (0, 1));

-- When the participant was last emailed about the thread.
ALTER TABLE thread_participant ADD COLUMN notified_at INTEGER;

-- Outbox: a pending email to a participant about messages in a thread,
-- written with the message. `message_id` is the latest message it covers.
CREATE TABLE notification (
  thread_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  message_id INTEGER NOT NULL REFERENCES message (id) ON DELETE CASCADE,
  due_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (thread_id, user_id),
  FOREIGN KEY (thread_id, user_id) REFERENCES thread_participant (thread_id, user_id) ON DELETE CASCADE
) STRICT, WITHOUT ROWID;

CREATE INDEX notification_due_at ON notification (due_at);
CREATE INDEX notification_message_id ON notification (message_id);
