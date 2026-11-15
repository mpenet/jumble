-- The status a favorite had when its member last looked at their
-- favorites: a different one now is news for them.
ALTER TABLE favorite ADD COLUMN seen_status TEXT NOT NULL DEFAULT 'active';
UPDATE favorite SET seen_status = (SELECT status FROM listing WHERE listing.id = favorite.listing_id);
