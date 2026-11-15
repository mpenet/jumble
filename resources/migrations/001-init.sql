-- Initial schema: users, sessions and sign-ins in progress, listings with
-- photos, wanted posts, and the full-text index over listings.
--
-- Timestamps are epoch milliseconds. Prices are integers in the minor unit
-- of the deployment's single currency (see setting). The application validates input for friendly
-- errors; the constraints here keep the data sound regardless.

-- Deployment-wide values recorded on first start that must not change
-- afterwards, such as the currency prices are stored in.
CREATE TABLE setting (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
) STRICT, WITHOUT ROWID;

CREATE TABLE user (
  id INTEGER PRIMARY KEY,
  iss TEXT NOT NULL,
  sub TEXT NOT NULL,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_login_at INTEGER NOT NULL,
  UNIQUE (iss, sub)
) STRICT;

-- token_hash is the SHA-256 of the cookie value; the token itself is never stored.
CREATE TABLE session (
  token_hash BLOB PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;

CREATE INDEX session_user_id ON session (user_id);
CREATE INDEX session_expires_at ON session (expires_at);

-- An OIDC sign-in in progress, keyed by the SHA-256 of its state parameter.
-- Single use: the callback deletes it. code_verifier never leaves the server.
CREATE TABLE login_attempt (
  state_hash BLOB PRIMARY KEY,
  nonce TEXT NOT NULL,
  code_verifier TEXT NOT NULL,
  return_to TEXT NOT NULL,
  expires_at INTEGER NOT NULL
) STRICT, WITHOUT ROWID;

CREATE INDEX login_attempt_expires_at ON login_attempt (expires_at);

CREATE TABLE listing (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id),
  kind TEXT NOT NULL CHECK (kind IN ('sale', 'free', 'lend')),
  title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
  price_minor INTEGER CHECK (price_minor > 0),
  -- Ids from configuration.
  category TEXT NOT NULL,
  pickup_office TEXT,
  pickup_city TEXT CHECK (length(pickup_city) BETWEEN 1 AND 80),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'sold', 'given', 'withdrawn')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  CHECK ((kind = 'sale') = (price_minor IS NOT NULL)),
  CHECK ((pickup_office IS NULL) <> (pickup_city IS NULL)),
  CHECK (status <> 'sold' OR kind = 'sale'),
  CHECK (status <> 'given' OR kind = 'free')
) STRICT;

CREATE INDEX listing_board ON listing (status, created_at);
CREATE INDEX listing_user_id ON listing (user_id);

-- object_key prefixes the stored sizes; width and height are the full size's.
-- At most five photos per listing, one per position.
CREATE TABLE photo (
  id INTEGER PRIMARY KEY,
  listing_id INTEGER NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
  position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 4),
  object_key TEXT NOT NULL UNIQUE,
  width INTEGER NOT NULL CHECK (width > 0),
  height INTEGER NOT NULL CHECK (height > 0),
  UNIQUE (listing_id, position)
) STRICT;

CREATE TABLE wanted (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES user (id),
  title TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX wanted_board ON wanted (status, created_at);
CREATE INDEX wanted_user_id ON wanted (user_id);

-- External-content index: text lives in listing only, triggers keep the
-- index in step. remove_diacritics 2 makes "velo" match "vélo".
CREATE VIRTUAL TABLE listing_fts USING fts5 (
  title,
  description,
  content = 'listing',
  content_rowid = 'id',
  tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER listing_fts_insert AFTER INSERT ON listing BEGIN
  INSERT INTO listing_fts (rowid, title, description)
  VALUES (new.id, new.title, new.description);
END;

CREATE TRIGGER listing_fts_delete AFTER DELETE ON listing BEGIN
  INSERT INTO listing_fts (listing_fts, rowid, title, description)
  VALUES ('delete', old.id, old.title, old.description);
END;

-- Only text edits reindex; status and price changes do not touch the index.
CREATE TRIGGER listing_fts_update AFTER UPDATE OF title, description ON listing BEGIN
  INSERT INTO listing_fts (listing_fts, rowid, title, description)
  VALUES ('delete', old.id, old.title, old.description);
  INSERT INTO listing_fts (rowid, title, description)
  VALUES (new.id, new.title, new.description);
END;
