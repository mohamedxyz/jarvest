-- Hand-picked order of goals within a category. Ties fall back to creation order.
ALTER TABLE goals ADD COLUMN position INTEGER NOT NULL DEFAULT 0;
