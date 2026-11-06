//! A private note and mood for a day. It is only ever shown back to the person and summed into the
//! mood lines of the insights. It never changes the score, XP, streaks or rewards.

use rusqlite::{Connection, OptionalExtension, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::invalid;
use crate::model::*;

const MAX_CHARS: usize = 2000;

/// Saves the note for `date`. Nothing in it (no text and no mood) removes the note.
pub fn set(
    conn: &Connection,
    date: &str,
    body: &str,
    mood: Option<u8>,
    today: &str,
) -> Result<(), CoreError> {
    let day = parse_date(date)?;
    if day > parse_date(today)? {
        return Err(invalid(
            "A note can't be written for a day that hasn't come yet",
        ));
    }
    if mood.is_some_and(|m| !(1..=5).contains(&m)) {
        return Err(invalid("A mood is a number from 1 to 5"));
    }
    let body = body.trim();
    if body.chars().count() > MAX_CHARS {
        return Err(invalid("A note can be at most 2000 characters"));
    }
    if body.is_empty() && mood.is_none() {
        conn.execute("DELETE FROM day_notes WHERE local_date = ?1", [date])?;
        return Ok(());
    }
    conn.execute(
        "INSERT INTO day_notes (local_date, body, mood) VALUES (?1, ?2, ?3)
         ON CONFLICT (local_date) DO UPDATE SET body = excluded.body, mood = excluded.mood",
        params![date, body, mood],
    )?;
    Ok(())
}

pub fn get(conn: &Connection, date: &str) -> Result<Option<DayNote>, CoreError> {
    parse_date(date)?;
    Ok(conn
        .query_row(
            "SELECT local_date, body, mood FROM day_notes WHERE local_date = ?1",
            [date],
            |r| {
                Ok(DayNote {
                    date: r.get(0)?,
                    body: r.get(1)?,
                    mood: r.get(2)?,
                })
            },
        )
        .optional()?)
}
