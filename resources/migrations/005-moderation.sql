-- Reports of listings to the admins, and emails waiting to be sent.

-- A report outlives the listing it is about: removing it is the outcome
-- most worth keeping. The title is kept for that reason.
CREATE TABLE report (
  id INTEGER PRIMARY KEY,
  listing_id INTEGER REFERENCES listing (id) ON DELETE SET NULL,
  listing_title TEXT NOT NULL,
  reporter_id INTEGER NOT NULL REFERENCES user (id),
  reason TEXT NOT NULL DEFAULT '' CHECK (length(reason) <= 1000),
  created_at INTEGER NOT NULL,
  resolution TEXT CHECK (resolution IN ('removed', 'dismissed')),
  resolved_by INTEGER REFERENCES user (id),
  resolved_at INTEGER,
  CHECK ((resolution IS NULL) = (resolved_at IS NULL) AND (resolution IS NULL) = (resolved_by IS NULL))
) STRICT;

-- One open report per person and listing.
CREATE UNIQUE INDEX report_open ON report (listing_id, reporter_id) WHERE resolution IS NULL;
CREATE INDEX report_open_created_at ON report (created_at) WHERE resolution IS NULL;

-- Emails written with what they are about, sent in the background.
CREATE TABLE email_outbox (
  id INTEGER PRIMARY KEY,
  recipient TEXT NOT NULL,
  subject TEXT NOT NULL,
  body TEXT NOT NULL,
  due_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE INDEX email_outbox_due_at ON email_outbox (due_at);
