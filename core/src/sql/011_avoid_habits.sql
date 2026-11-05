-- Quit (avoid) habits. An avoid goal is a daily habit that is met by NOT logging:
-- every logged entry is a slip, and a day with no slip is a clean day.
ALTER TABLE goals ADD COLUMN avoid INTEGER NOT NULL DEFAULT 0;
