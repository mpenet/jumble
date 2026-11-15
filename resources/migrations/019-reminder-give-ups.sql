-- How many times in a row the mail server refused a listing's freshness
-- reminder: asked again until the third, the owner then counts as not
-- answering. Back to 0 with any news of the listing.
ALTER TABLE listing ADD COLUMN reminder_give_ups INTEGER NOT NULL DEFAULT 0;
