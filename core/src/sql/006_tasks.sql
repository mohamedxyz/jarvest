-- One-off and repeating to-do items. Kept apart from goals: tasks never earn
-- rewards and never count toward streaks or stats.
CREATE TABLE tasks (
    id           TEXT PRIMARY KEY,
    title        TEXT NOT NULL,
    notes        TEXT NOT NULL DEFAULT '',
    due          TEXT,
    priority     TEXT NOT NULL DEFAULT 'med' CHECK (priority IN ('low', 'med', 'high')),
    tag          TEXT,
    repeat       TEXT CHECK (repeat IN ('daily', 'weekdays', 'weekly', 'monthly')),
    created_date TEXT NOT NULL,
    done_date    TEXT,
    -- A deleted task stays here (in the trash) until it is purged.
    deleted_date TEXT,
    -- Links between the copies of a repeating task.
    next_id      TEXT,
    prev_id      TEXT
) STRICT;
CREATE INDEX tasks_by_due ON tasks (due);

CREATE TABLE task_items (
    id       TEXT PRIMARY KEY,
    task_id  TEXT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    text     TEXT NOT NULL,
    position INTEGER NOT NULL,
    done     INTEGER NOT NULL DEFAULT 0 CHECK (done IN (0, 1))
) STRICT;
CREATE INDEX task_items_by_task ON task_items (task_id, position);
