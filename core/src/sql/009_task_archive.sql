-- Task power features (version 0.6.0).
-- An archived task is hidden from the lists but not deleted, and can be restored.
ALTER TABLE tasks ADD COLUMN archived_date TEXT;
-- How many times the due date was moved to a later day.
ALTER TABLE tasks ADD COLUMN pushed_count INTEGER NOT NULL DEFAULT 0;
-- Custom repeat: "every N" days, weeks or months (the unit is the `repeat` column).
ALTER TABLE tasks ADD COLUMN repeat_every INTEGER NOT NULL DEFAULT 1
    CHECK (repeat_every BETWEEN 1 AND 365);
-- Weekly repeats can name weekdays (Monday = 1 up to Sunday = 64). NULL means no choice.
ALTER TABLE tasks ADD COLUMN repeat_days INTEGER
    CHECK (repeat_days IS NULL OR repeat_days BETWEEN 1 AND 127);
