-- Emails of use for a while only, such as sign-in links: sent before the
-- others, and dropped unsent once expired. NULL: no expiry.
ALTER TABLE email_outbox ADD COLUMN expires_at INTEGER;
