-- Days a goal is skipped or paused. An unmet period that contains a skipped day is
-- excused: it neither breaks the streak nor counts in the completion rate.
CREATE TABLE skips (
    goal_id    TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    local_date TEXT NOT NULL,
    PRIMARY KEY (goal_id, local_date)
) STRICT;
