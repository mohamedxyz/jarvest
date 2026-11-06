-- XP, hero levels and the weekly boss.
-- One row per day with the points that day ended with. Today's row is rewritten while the day
-- goes on. Older rows stay as they were, so XP and the calendar don't change when plans change.
CREATE TABLE day_scores (
    local_date TEXT PRIMARY KEY,
    earned     INTEGER NOT NULL CHECK (earned >= 0),
    possible   INTEGER NOT NULL CHECK (possible >= 0),
    level      TEXT NOT NULL CHECK (level IN ('none', 'spark', 'blaze', 'titan'))
) STRICT;

-- A boss appears the first time the app runs in a week (weeks start on Monday).
CREATE TABLE weekly_bosses (
    week_start    TEXT PRIMARY KEY,
    health        INTEGER NOT NULL CHECK (health > 0),
    defeated_date TEXT
) STRICT;

-- The treat won for beating a weekly boss. It shows in the treat list like any other draw.
CREATE TABLE boss_prizes (
    id            TEXT PRIMARY KEY,
    week_start    TEXT NOT NULL UNIQUE REFERENCES weekly_bosses (week_start) ON DELETE CASCADE,
    treat_id      TEXT NOT NULL REFERENCES treats (id),
    local_date    TEXT NOT NULL,
    seen          INTEGER NOT NULL DEFAULT 0 CHECK (seen IN (0, 1)),
    redeemed_date TEXT
) STRICT;
