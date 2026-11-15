-- The weekly email of what is new on the board: on unless a member turns
-- it off, and when it was last considered for them (sent, or nothing new).
ALTER TABLE user ADD COLUMN weekly_digest INTEGER NOT NULL DEFAULT 1;
ALTER TABLE user ADD COLUMN digest_sent_at INTEGER;
