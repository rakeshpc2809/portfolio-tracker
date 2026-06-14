-- Add deleted column to investor table for SoftDelete support
ALTER TABLE investor ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
UPDATE investor SET deleted = false WHERE deleted IS NULL;

-- Add deleted column to tax_lot table for SoftDelete support
ALTER TABLE tax_lot ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
UPDATE tax_lot SET deleted = false WHERE deleted IS NULL;
