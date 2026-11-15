-- The language a member reads Jumble in: their pages and the emails to them.
-- NULL until they choose one, following the community's.
ALTER TABLE user ADD COLUMN language TEXT;
