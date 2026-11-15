-- When a member deleted their account. The row stays, scrubbed, so what
-- they wrote to others still has a sender: "A former member".
ALTER TABLE user ADD COLUMN deleted_at INTEGER;
