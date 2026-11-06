-- A routine is an ordered list of goals done together, such as a morning routine.
-- Ticking a step is an ordinary tick of that goal, so a routine never changes rewards or XP.
CREATE TABLE routines (
    id       TEXT PRIMARY KEY,
    name     TEXT NOT NULL,
    position INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE TABLE routine_steps (
    routine_id TEXT NOT NULL REFERENCES routines (id) ON DELETE CASCADE,
    goal_id    TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    position   INTEGER NOT NULL,
    PRIMARY KEY (routine_id, goal_id)
) STRICT;
