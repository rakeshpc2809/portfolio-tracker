-- V6__Add_Soft_Delete_Defaults.sql
-- Set default value of FALSE for the deleted columns to ensure raw JDBC / SQL inserts succeed.

ALTER TABLE folio ALTER COLUMN deleted SET DEFAULT false;
ALTER TABLE stock ALTER COLUMN deleted SET DEFAULT false;
ALTER TABLE stock_transaction ALTER COLUMN deleted SET DEFAULT false;
ALTER TABLE transaction ALTER COLUMN deleted SET DEFAULT false;

-- Set values to false if any are currently null (precautionary)
UPDATE folio SET deleted = false WHERE deleted IS NULL;
UPDATE stock SET deleted = false WHERE deleted IS NULL;
UPDATE stock_transaction SET deleted = false WHERE deleted IS NULL;
UPDATE transaction SET deleted = false WHERE deleted IS NULL;
