-- Accounts found by address (signing in by email, removing a member) without
-- reading every account. Addresses are stored lowercase.
CREATE INDEX user_email ON user (email);
