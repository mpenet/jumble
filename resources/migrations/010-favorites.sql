-- Listings members keep an eye on, private to each: never counted or shown
-- to the owner. Gone with the member or the listing.
CREATE TABLE favorite (
  user_id INTEGER NOT NULL REFERENCES user (id) ON DELETE CASCADE,
  listing_id INTEGER NOT NULL REFERENCES listing (id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, listing_id)
) STRICT, WITHOUT ROWID;

-- Removing a listing looks its favorites up.
CREATE INDEX favorite_listing_id ON favorite (listing_id);
