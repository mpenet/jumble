-- When a listing was last known to be available: posted, edited, put back
-- on the board, or confirmed by its owner; and when its owner was asked
-- whether it still is. Unanswered, it expires (see listing/freshness.clj).
ALTER TABLE listing ADD COLUMN fresh_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE listing ADD COLUMN reminded_at INTEGER;
UPDATE listing SET fresh_at = updated_at;

-- Listings on the board, oldest news first, for the reminders.
CREATE INDEX listing_freshness ON listing (fresh_at) WHERE status IN ('active', 'reserved');
