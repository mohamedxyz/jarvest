-- XP bonuses and the daily quest.
-- A bonus is one row per day and kind, so asking again never pays twice. Bonuses for the day in
-- progress (a Titan day, the quest, a streak) are removed again if the day is undone. Boss and monster
-- bonuses are permanent, like the wins they come from.
CREATE TABLE xp_bonuses (
    local_date TEXT NOT NULL,
    kind       TEXT NOT NULL,
    points     INTEGER NOT NULL CHECK (points > 0),
    PRIMARY KEY (local_date, kind)
) STRICT;

-- One small suggested target a day. It is made the first time the day has something planned.
CREATE TABLE daily_quests (
    local_date TEXT PRIMARY KEY,
    kind       TEXT NOT NULL CHECK (kind IN ('points', 'tasks', 'goals')),
    target     INTEGER NOT NULL CHECK (target > 0)
) STRICT;
