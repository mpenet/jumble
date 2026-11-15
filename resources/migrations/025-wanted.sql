-- Wanted posts: listings of what someone looks for, without a price, found
-- once someone had one. SQLite cannot change a CHECK constraint: the
-- listing and saved_search tables are rebuilt, as in
-- 011-listing-statuses.sql, with their columns, indexes and triggers.
CREATE TABLE listing_rebuilt (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id),
  kind TEXT NOT NULL CHECK (kind IN ('sale', 'free', 'lend', 'wanted')),
  title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
  price_minor INTEGER CHECK (price_minor > 0),
  category TEXT NOT NULL REFERENCES category (id),
  pickup_place TEXT REFERENCES place (id),
  pickup_city TEXT CHECK (length(pickup_city) BETWEEN 1 AND 80),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'reserved', 'sold', 'given', 'found', 'expired')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  fresh_at INTEGER NOT NULL DEFAULT 0,
  reminded_at INTEGER,
  reminder_give_ups INTEGER NOT NULL DEFAULT 0,
  CHECK ((kind = 'sale') = (price_minor IS NOT NULL)),
  CHECK ((pickup_place IS NULL) <> (pickup_city IS NULL)),
  CHECK (status <> 'sold' OR kind = 'sale'),
  CHECK (status <> 'given' OR kind = 'free'),
  CHECK (status <> 'found' OR kind = 'wanted')
) STRICT;

INSERT INTO listing_rebuilt (id, user_id, kind, title, description, price_minor, category, pickup_place,
                             pickup_city, status, created_at, updated_at, fresh_at, reminded_at,
                             reminder_give_ups)
SELECT id, user_id, kind, title, description, price_minor, category, pickup_place,
       pickup_city, status, created_at, updated_at, fresh_at, reminded_at, reminder_give_ups
FROM listing;

-- Takes the listing's indexes and triggers with it; the search index keeps
-- its rows, keyed by listing id, and photos, threads, reports, favorites and
-- the outbox their references, by table name.
DROP TABLE listing;
ALTER TABLE listing_rebuilt RENAME TO listing;

-- The board: listings on it, newest first.
CREATE INDEX listing_board ON listing (created_at, id) WHERE status IN ('active', 'reserved');
CREATE INDEX listing_freshness ON listing (fresh_at) WHERE status IN ('active', 'reserved');
CREATE INDEX listing_user_id ON listing (user_id);

CREATE TRIGGER listing_fts_insert AFTER INSERT ON listing BEGIN
  INSERT INTO listing_fts (rowid, title, description)
  VALUES (new.id, new.title, new.description);
END;

CREATE TRIGGER listing_fts_delete AFTER DELETE ON listing BEGIN
  INSERT INTO listing_fts (listing_fts, rowid, title, description)
  VALUES ('delete', old.id, old.title, old.description);
END;

CREATE TRIGGER listing_fts_update AFTER UPDATE OF title, description ON listing BEGIN
  INSERT INTO listing_fts (listing_fts, rowid, title, description)
  VALUES ('delete', old.id, old.title, old.description);
  INSERT INTO listing_fts (rowid, title, description)
  VALUES (new.id, new.title, new.description);
END;

-- As in 008-reports-on-removal.sql.
CREATE TRIGGER report_resolve_on_listing_delete BEFORE DELETE ON listing
BEGIN
  UPDATE report
  SET resolution = 'removed', resolved_by = OLD.user_id,
      resolved_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
  WHERE listing_id = OLD.id AND resolution IS NULL;
END;

CREATE TABLE saved_search_rebuilt (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  q TEXT CHECK (length(q) BETWEEN 1 AND 100),
  kind TEXT CHECK (kind IN ('sale', 'free', 'lend', 'wanted')),
  category TEXT REFERENCES category (id),
  created_at INTEGER NOT NULL,
  CHECK (q IS NOT NULL OR kind IS NOT NULL OR category IS NOT NULL)
) STRICT;

INSERT INTO saved_search_rebuilt (id, user_id, q, kind, category, created_at)
SELECT id, user_id, q, kind, category, created_at FROM saved_search;

DROP TABLE saved_search;
ALTER TABLE saved_search_rebuilt RENAME TO saved_search;

CREATE UNIQUE INDEX saved_search_unique
  ON saved_search (user_id, coalesce(q, ''), coalesce(kind, ''), coalesce(category, ''));
