-- Photo objects to delete from storage, by their key (both sizes): those of
-- removed photos, queued in the transaction removing them, until storage
-- deleted them; and uploads, due an hour later unless the transaction
-- recording their listing takes them off, should the process die between.
CREATE TABLE object_deletion (
  object_key TEXT PRIMARY KEY,
  due_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
) STRICT, WITHOUT ROWID;

CREATE INDEX object_deletion_due_at ON object_deletion (due_at);
