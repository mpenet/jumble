-- Sign-in links sent by email, for instances signing in by email rather
-- than with an OpenID Connect provider. Only the token's hash is stored.
-- Rows stay until they expire, used or not, so recent requests can be
-- counted to limit how many links are sent.
CREATE TABLE email_sign_in (
  token_hash BLOB PRIMARY KEY,
  email TEXT NOT NULL,
  return_to TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0 CHECK (used IN (0, 1))
) STRICT, WITHOUT ROWID;

CREATE INDEX email_sign_in_email ON email_sign_in (email, created_at);
CREATE INDEX email_sign_in_expires_at ON email_sign_in (expires_at);
