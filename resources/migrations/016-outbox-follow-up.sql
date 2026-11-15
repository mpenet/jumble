-- What an email is about, for those that matter after sending: a listing's
-- freshness reminder (gone with the listing). And emails given up on are
-- kept a while, marked, so what they were about can be asked again.
ALTER TABLE email_outbox ADD COLUMN listing_id INTEGER REFERENCES listing (id) ON DELETE CASCADE;
ALTER TABLE email_outbox ADD COLUMN given_up_at INTEGER;

CREATE INDEX email_outbox_listing_id ON email_outbox (listing_id) WHERE listing_id IS NOT NULL;
