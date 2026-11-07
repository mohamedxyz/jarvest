-- 0.12.0: time of day on logs, and the save-up shop.
-- The minute of the day a log was made (0 to 1439), passed in by the app. Logs made
-- before this version, and past days filled in later, have none.
ALTER TABLE entries ADD COLUMN minute INTEGER CHECK (minute IS NULL OR minute BETWEEN 0 AND 1439);
-- A treat with a price can be bought in the shop with coins (1 coin per 10 XP).
ALTER TABLE treats ADD COLUMN price INTEGER CHECK (price IS NULL OR price BETWEEN 1 AND 100000);
-- Treats bought in the shop. Like other wins, they wait in the stock until used.
CREATE TABLE shop_purchases (
    id            TEXT PRIMARY KEY,
    treat_id      TEXT NOT NULL REFERENCES treats (id),
    price         INTEGER NOT NULL CHECK (price > 0),
    local_date    TEXT NOT NULL,
    redeemed_date TEXT
) STRICT;
