-- Search as one types ends with a prefix query ("bi*"): two- and
-- three-letter prefixes get an index of their own, rather than a scan of
-- the terms. The triggers on listing keep it in step, as before.
DROP TABLE listing_fts;

CREATE VIRTUAL TABLE listing_fts USING fts5 (
  title,
  description,
  content = 'listing',
  content_rowid = 'id',
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3'
);

INSERT INTO listing_fts (listing_fts) VALUES ('rebuild');
