-- The allowlist says the way each address came in: added by a moderator,
-- joined by the join link, or invited by a member.

ALTER TABLE allowed_email ADD COLUMN way TEXT NOT NULL DEFAULT 'moderator'
  CHECK (way IN ('moderator', 'link', 'invitation'));
UPDATE allowed_email SET way = 'link' WHERE joined_by_link = 1;
ALTER TABLE allowed_email DROP COLUMN joined_by_link;
