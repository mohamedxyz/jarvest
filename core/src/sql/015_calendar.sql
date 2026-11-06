-- Calendar and time blocking.
-- A task can have a start time (minutes after midnight) on its due day and a length in minutes.
ALTER TABLE tasks ADD COLUMN due_time INTEGER CHECK (due_time IS NULL OR due_time BETWEEN 0 AND 1439);
ALTER TABLE tasks ADD COLUMN duration INTEGER CHECK (duration IS NULL OR duration BETWEEN 5 AND 1440);
-- A goal or habit can have a time block on the days it is planned.
ALTER TABLE goals ADD COLUMN block_start INTEGER CHECK (block_start IS NULL OR block_start BETWEEN 0 AND 1439);
ALTER TABLE goals ADD COLUMN block_minutes INTEGER CHECK (block_minutes IS NULL OR block_minutes BETWEEN 5 AND 1440);

-- Events: things on the calendar that are not tasks. They never affect the score, XP, streaks or rewards.
CREATE TABLE events (
    id            TEXT PRIMARY KEY,
    title         TEXT NOT NULL,
    start_date    TEXT NOT NULL,
    start_minutes INTEGER NOT NULL CHECK (start_minutes BETWEEN 0 AND 1439),
    minutes       INTEGER NOT NULL CHECK (minutes BETWEEN 5 AND 1440),
    repeat        TEXT NOT NULL DEFAULT 'none' CHECK (repeat IN ('none', 'daily', 'weekly')),
    -- For a weekly event: weekdays as a mask (Monday = 1 up to Sunday = 64). NULL means the start date's weekday.
    weekdays      INTEGER
) STRICT;
