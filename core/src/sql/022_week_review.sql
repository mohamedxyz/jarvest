-- The weekly review. A row exists once the review was finished or dismissed, so it is offered once a week.
CREATE TABLE week_reviews (
    week_start     TEXT PRIMARY KEY,
    note           TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 2000),
    finished_date  TEXT,
    dismissed_date TEXT
) STRICT;

-- Up to three things to focus on in a week. Items that are later deleted simply drop off the list.
CREATE TABLE week_focus (
    week_start TEXT NOT NULL,
    position   INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3),
    kind       TEXT NOT NULL CHECK (kind IN ('goal', 'task')),
    item_id    TEXT NOT NULL,
    PRIMARY KEY (week_start, position)
) STRICT;
