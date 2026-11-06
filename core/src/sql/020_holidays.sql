-- Holiday mode: pause every goal for a stretch of days in one action.
-- A holiday remembers which skipped days it added, so ending it removes only those. A day the
-- person skipped by hand stays skipped.
CREATE TABLE holidays (
    id         TEXT PRIMARY KEY,
    start_date TEXT NOT NULL,
    end_date   TEXT NOT NULL,
    CHECK (end_date >= start_date)
) STRICT;

CREATE TABLE holiday_skips (
    holiday_id TEXT NOT NULL REFERENCES holidays (id) ON DELETE CASCADE,
    goal_id    TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    local_date TEXT NOT NULL,
    PRIMARY KEY (holiday_id, goal_id, local_date)
) STRICT;
