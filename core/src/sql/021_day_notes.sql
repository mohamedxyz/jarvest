-- A private note and an optional mood (1 to 5) for a day. Never used for score, XP, streaks or rewards.
CREATE TABLE day_notes (
    local_date TEXT PRIMARY KEY,
    body       TEXT NOT NULL DEFAULT '' CHECK (length(body) <= 2000),
    mood       INTEGER CHECK (mood IS NULL OR mood BETWEEN 1 AND 5),
    CHECK (body <> '' OR mood IS NOT NULL)
) STRICT;
