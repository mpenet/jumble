-- Categories and pickup places, managed by moderators in the app. Ids are
-- stored on listings and never change; labels can be renamed. A retired
-- entry is no longer offered, and its listings keep showing its label.
CREATE TABLE category (
  id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 60 AND id NOT GLOB '*[^a-z0-9-]*'),
  label TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(label) BETWEEN 1 AND 60),
  position INTEGER NOT NULL,
  retired_at INTEGER
) STRICT, WITHOUT ROWID;

CREATE TABLE place (
  id TEXT PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 60 AND id NOT GLOB '*[^a-z0-9-]*'),
  label TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(label) BETWEEN 1 AND 60),
  position INTEGER NOT NULL,
  retired_at INTEGER
) STRICT, WITHOUT ROWID;

INSERT INTO category (id, label, position) VALUES
  ('electronics', 'Electronics', 1),
  ('home', 'Furniture & home', 2),
  ('kids', 'Kids & baby', 3),
  ('sports', 'Sports & outdoors', 4),
  ('books-media', 'Books & media', 5),
  ('clothing', 'Clothing', 6),
  ('tickets', 'Tickets & vouchers', 7),
  ('other', 'Other', 8);

-- Ids listings use that are not among the defaults, named by their id for
-- moderators to rename.
INSERT INTO category (id, label, position)
SELECT category, category, 8 + row_number() OVER (ORDER BY category)
FROM (SELECT DISTINCT category FROM listing WHERE category NOT IN (SELECT id FROM category));

INSERT INTO place (id, label, position)
SELECT pickup_place, pickup_place, row_number() OVER (ORDER BY pickup_place)
FROM (SELECT DISTINCT pickup_place FROM listing WHERE pickup_place IS NOT NULL);

-- The listing table rebuilt with references to both, as SQLite cannot add
-- them to a table (foreign keys are off while migrations run). The status
-- withdrawn, never set, goes.
CREATE TABLE listing_rebuilt (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id),
  kind TEXT NOT NULL CHECK (kind IN ('sale', 'free', 'lend')),
  title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
  price_minor INTEGER CHECK (price_minor > 0),
  category TEXT NOT NULL REFERENCES category (id),
  pickup_place TEXT REFERENCES place (id),
  pickup_city TEXT CHECK (length(pickup_city) BETWEEN 1 AND 80),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'sold', 'given')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  CHECK ((kind = 'sale') = (price_minor IS NOT NULL)),
  CHECK ((pickup_place IS NULL) <> (pickup_city IS NULL)),
  CHECK (status <> 'sold' OR kind = 'sale'),
  CHECK (status <> 'given' OR kind = 'free')
) STRICT;

INSERT INTO listing_rebuilt (id, user_id, kind, title, description, price_minor, category, pickup_place,
                             pickup_city, status, created_at, updated_at)
SELECT id, user_id, kind, title, description, price_minor, category, pickup_place,
       pickup_city, status, created_at, updated_at
FROM listing;

-- Takes the listing's indexes and triggers with it; the search index keeps
-- its rows, keyed by listing id.
DROP TABLE listing;
ALTER TABLE listing_rebuilt RENAME TO listing;

CREATE INDEX listing_board ON listing (status, created_at);
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
