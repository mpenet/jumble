-- Moderators are members the owner chose: they handle reports, listings,
-- members, categories and places.
ALTER TABLE user ADD COLUMN moderator INTEGER NOT NULL DEFAULT 0 CHECK (moderator IN (0, 1));
