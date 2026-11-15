-- Listings whose owner was asked whether they are still available, found
-- without reading the board: the daily count of questions, and those
-- expiring unanswered.
CREATE INDEX listing_reminded_at ON listing (reminded_at) WHERE reminded_at IS NOT NULL;
