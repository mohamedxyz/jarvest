-- Task templates: a saved task (title, notes, priority, tag, length and steps) to reuse.
CREATE TABLE task_templates (
    id       TEXT PRIMARY KEY,
    name     TEXT NOT NULL UNIQUE,
    title    TEXT NOT NULL,
    notes    TEXT NOT NULL DEFAULT '',
    priority TEXT NOT NULL DEFAULT 'med',
    tag      TEXT,
    minutes  INTEGER CHECK (minutes IS NULL OR minutes BETWEEN 5 AND 1440),
    -- One step per line.
    steps    TEXT NOT NULL DEFAULT ''
) STRICT;
