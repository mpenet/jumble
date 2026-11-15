-- Pickup is at any place the organisation configures.
-- SQLite rewrites the table's CHECK constraints to the new name.
ALTER TABLE listing RENAME COLUMN pickup_office TO pickup_place;
