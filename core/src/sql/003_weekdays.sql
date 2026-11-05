-- Which weekdays a daily habit is scheduled on, as a bitmask: Monday is 1, Tuesday 2,
-- Wednesday 4, up to Sunday 64. NULL means every day. 127 is stored as NULL.
ALTER TABLE goals ADD COLUMN weekdays INTEGER CHECK (weekdays IS NULL OR weekdays BETWEEN 1 AND 126);
