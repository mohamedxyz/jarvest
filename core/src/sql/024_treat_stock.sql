-- A treat can be one time only: once it is won (from a target, a boss or a monster),
-- it leaves the jar. Undoing the win puts it back. 0 means it can be won again and again.
ALTER TABLE treats ADD COLUMN once INTEGER NOT NULL DEFAULT 0 CHECK (once IN (0, 1));
