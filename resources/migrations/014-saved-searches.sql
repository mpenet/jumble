-- Searches members keep, to be emailed what is new for them once a day.
CREATE TABLE saved_search (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  q TEXT CHECK (length(q) BETWEEN 1 AND 100),
  kind TEXT CHECK (kind IN ('sale', 'free', 'lend')),
  category TEXT REFERENCES category (id),
  created_at INTEGER NOT NULL,
  CHECK (q IS NOT NULL OR kind IS NOT NULL OR category IS NOT NULL)
) STRICT;

-- One of each per member; NULLs are distinct in UNIQUE, hence coalesce.
CREATE UNIQUE INDEX saved_search_unique
  ON saved_search (user_id, coalesce(q, ''), coalesce(kind, ''), coalesce(category, ''));

-- Listings posted after this are new for the member's saved searches.
ALTER TABLE user ADD COLUMN searches_checked_at INTEGER;
