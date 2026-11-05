CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;

CREATE TABLE goals (
    id            TEXT PRIMARY KEY,
    title         TEXT NOT NULL,
    notes         TEXT NOT NULL DEFAULT '',
    kind          TEXT NOT NULL CHECK (kind IN ('yes_no', 'habit', 'numeric')),
    category      TEXT,
    period        TEXT CHECK (period IN ('day', 'week', 'month')),
    period_target REAL,
    total_target  REAL,
    unit          TEXT,
    deadline      TEXT,
    created_date  TEXT NOT NULL,
    archived      INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    CHECK ((period IS NULL) = (period_target IS NULL))
) STRICT;

CREATE TABLE milestones (
    id        TEXT PRIMARY KEY,
    goal_id   TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    title     TEXT NOT NULL,
    position  INTEGER NOT NULL,
    done_date TEXT
) STRICT;
CREATE INDEX milestones_by_goal ON milestones (goal_id, position);

CREATE TABLE entries (
    id         TEXT PRIMARY KEY,
    goal_id    TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    local_date TEXT NOT NULL,
    amount     REAL NOT NULL,
    single_per_day INTEGER NOT NULL CHECK (single_per_day IN (0, 1)),
    seq        INTEGER NOT NULL
) STRICT;
CREATE INDEX entries_by_goal_date ON entries (goal_id, local_date);
CREATE INDEX entries_by_date ON entries (local_date);
-- Yes/no goals and habits can only be logged once per day.
CREATE UNIQUE INDEX entries_one_check_in_per_day
    ON entries (goal_id, local_date) WHERE single_per_day = 1;

CREATE TABLE target_completions (
    id         TEXT PRIMARY KEY,
    goal_id    TEXT NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    target_key TEXT NOT NULL,
    local_date TEXT NOT NULL,
    -- 'none' (no draw), 'draw' (treat won), 'jar_empty', or 'rewards_off'.
    outcome    TEXT NOT NULL CHECK (outcome IN ('none', 'draw', 'jar_empty', 'rewards_off')),
    seq        INTEGER NOT NULL,
    UNIQUE (goal_id, target_key)
) STRICT;

CREATE TABLE treats (
    id      TEXT PRIMARY KEY,
    title   TEXT NOT NULL,
    removed INTEGER NOT NULL DEFAULT 0 CHECK (removed IN (0, 1)),
    seq     INTEGER NOT NULL
) STRICT;

CREATE TABLE reward_draws (
    id            TEXT PRIMARY KEY,
    completion_id TEXT NOT NULL UNIQUE REFERENCES target_completions (id) ON DELETE CASCADE,
    treat_id      TEXT NOT NULL REFERENCES treats (id),
    local_date    TEXT NOT NULL,
    comeback      INTEGER NOT NULL CHECK (comeback IN (0, 1)),
    seen          INTEGER NOT NULL DEFAULT 0 CHECK (seen IN (0, 1)),
    redeemed_date TEXT
) STRICT;
