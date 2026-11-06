-- Calendar, second round: repeating events can end, one day of a repeat can be skipped,
-- and an event can remind you some minutes before it starts.
ALTER TABLE events ADD COLUMN until_date TEXT;
ALTER TABLE events ADD COLUMN remind_minutes INTEGER CHECK (remind_minutes IS NULL OR remind_minutes BETWEEN 0 AND 1440);

-- A day on which a repeating event does not happen.
CREATE TABLE event_skips (
    event_id  TEXT NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    skip_date TEXT NOT NULL,
    PRIMARY KEY (event_id, skip_date)
) STRICT;
