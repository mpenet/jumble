-- Each new post announced on each chat channel on its own: the webhook's
-- and IRC's announcements fail and succeed apart.

ALTER TABLE chat_announcement ADD COLUMN channel TEXT NOT NULL DEFAULT 'webhook';

DROP INDEX chat_announcement_due_at;
CREATE INDEX chat_announcement_channel_due_at ON chat_announcement (channel, due_at);
