-- Custom monsters: a struggle the person names and fights by linking goals, habits and tasks.
-- Health and damage are worked out from history, so only the rounds are stored.
CREATE TABLE monsters (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    base_health INTEGER NOT NULL CHECK (base_health > 0),
    created_date TEXT NOT NULL,
    -- A treat in the jar that is the prize for beating this monster, or NULL.
    prize_treat_id TEXT REFERENCES treats (id),
    -- Set when the final boss is beaten. The monster never respawns after that.
    retired_date TEXT
) STRICT;

CREATE TABLE monster_links (
    monster_id TEXT NOT NULL REFERENCES monsters (id) ON DELETE CASCADE,
    kind       TEXT NOT NULL CHECK (kind IN ('goal', 'task')),
    item_id    TEXT NOT NULL,
    power      INTEGER NOT NULL CHECK (power IN (5, 10, 20)),
    PRIMARY KEY (monster_id, kind, item_id)
) STRICT;

CREATE TABLE monster_rounds (
    monster_id    TEXT NOT NULL REFERENCES monsters (id) ON DELETE CASCADE,
    round         INTEGER NOT NULL,
    start_date    TEXT NOT NULL,
    health        INTEGER NOT NULL CHECK (health > 0),
    final_boss    INTEGER NOT NULL DEFAULT 0 CHECK (final_boss IN (0, 1)),
    defeated_date TEXT,
    PRIMARY KEY (monster_id, round)
) STRICT;

-- Treats won by beating a monster. They show up in the treat list like any other draw.
CREATE TABLE monster_prizes (
    id            TEXT PRIMARY KEY,
    monster_id    TEXT NOT NULL REFERENCES monsters (id) ON DELETE CASCADE,
    round         INTEGER NOT NULL,
    slot          INTEGER NOT NULL,
    treat_id      TEXT NOT NULL REFERENCES treats (id),
    local_date    TEXT NOT NULL,
    seen          INTEGER NOT NULL DEFAULT 0 CHECK (seen IN (0, 1)),
    redeemed_date TEXT,
    UNIQUE (monster_id, round, slot)
) STRICT;
