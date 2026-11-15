-- What moderators choose for the whole community, one value per name: its
-- palette.
CREATE TABLE community_setting (
  name TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
