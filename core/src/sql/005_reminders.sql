-- Optional reminder time for a goal, as minutes after midnight (0 to 1439).
CREATE TABLE reminders (
    goal_id TEXT PRIMARY KEY REFERENCES goals (id) ON DELETE CASCADE,
    minutes INTEGER NOT NULL CHECK (minutes BETWEEN 0 AND 1439)
) STRICT;
