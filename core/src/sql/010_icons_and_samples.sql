-- A small emoji icon for goals and tasks, and a flag marking sample data.
-- Rows with sample = 1 come from "Try it with sample data": they can be cleared in
-- one tap, or kept (which sets the flag back to 0). Real data is never flagged.
ALTER TABLE goals ADD COLUMN icon TEXT;
ALTER TABLE goals ADD COLUMN sample INTEGER NOT NULL DEFAULT 0 CHECK (sample IN (0, 1));
ALTER TABLE tasks ADD COLUMN icon TEXT;
ALTER TABLE tasks ADD COLUMN sample INTEGER NOT NULL DEFAULT 0 CHECK (sample IN (0, 1));
