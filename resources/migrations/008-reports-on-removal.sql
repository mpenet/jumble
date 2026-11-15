-- Reports of a listing its owner removes are resolved with it, as removed by
-- the owner: otherwise they would stay open with no listing to show, where
-- no moderator ever sees them. Moderators resolve theirs before removing.
CREATE TRIGGER report_resolve_on_listing_delete BEFORE DELETE ON listing
BEGIN
  UPDATE report
  SET resolution = 'removed', resolved_by = OLD.user_id,
      resolved_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
  WHERE listing_id = OLD.id AND resolution IS NULL;
END;

-- Removing a listing looks its reports up.
CREATE INDEX report_listing_id ON report (listing_id);

-- Wanted posts were planned, never built.
DROP TABLE wanted;
