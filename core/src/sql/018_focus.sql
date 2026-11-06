-- Focus sessions: time spent focusing on a task or a goal. Only finished or stopped timers are stored.
-- The running timer itself lives in the app, so there is nothing to clean up after a crash.
CREATE TABLE focus_sessions (
    id            TEXT PRIMARY KEY,
    kind          TEXT NOT NULL CHECK (kind IN ('task', 'goal')),
    item_id       TEXT NOT NULL,
    -- A copy of the title at the time, so history still reads well after the item is deleted.
    title         TEXT NOT NULL,
    local_date    TEXT NOT NULL,
    start_minutes INTEGER NOT NULL CHECK (start_minutes BETWEEN 0 AND 1439),
    minutes       INTEGER NOT NULL CHECK (minutes BETWEEN 1 AND 600)
) STRICT;
CREATE INDEX focus_by_date ON focus_sessions (local_date);
CREATE INDEX focus_by_item ON focus_sessions (item_id);
