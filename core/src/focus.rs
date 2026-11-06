//! Focus sessions. Time spent focusing is recorded only; it never earns points, XP or treats,
//! so the day score stays about what you finished. `date` and times come from the caller.

use chrono::Days;
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::{invalid, new_id};
use crate::model::*;

/// Saves one session. `title` is copied so history survives deleting the item.
pub fn record(
    conn: &Connection,
    kind: FocusKind,
    item_id: &str,
    title: &str,
    date: &str,
    start_minutes: u32,
    minutes: u32,
) -> Result<String, CoreError> {
    parse_date(date)?;
    if !(1..=600).contains(&minutes) {
        return Err(invalid("A focus session is 1 to 600 minutes"));
    }
    if start_minutes > 1439 {
        return Err(invalid("Invalid start time"));
    }
    let exists: bool = match kind {
        FocusKind::Task => conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ?1)",
            [item_id],
            |r| r.get(0),
        )?,
        FocusKind::Goal => conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM goals WHERE id = ?1)",
            [item_id],
            |r| r.get(0),
        )?,
    };
    if !exists {
        return Err(invalid("That task or goal no longer exists"));
    }
    let id = new_id();
    conn.execute(
        "INSERT INTO focus_sessions (id, kind, item_id, title, local_date, start_minutes, minutes)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            id,
            match kind {
                FocusKind::Task => "task",
                FocusKind::Goal => "goal",
            },
            item_id,
            title.trim().chars().take(120).collect::<String>(),
            date,
            start_minutes,
            minutes
        ],
    )?;
    Ok(id)
}

/// Total focus minutes on one task or goal, ever.
pub fn total_for(conn: &Connection, item_id: &str) -> Result<u32, CoreError> {
    Ok(conn.query_row(
        "SELECT COALESCE(SUM(minutes), 0) FROM focus_sessions WHERE item_id = ?1",
        [item_id],
        |r| r.get(0),
    )?)
}

/// Focus minutes for each day from `from` for `days` days (at most 62). Every day is listed, even with 0.
pub fn by_day(conn: &Connection, from: &str, days: u32) -> Result<Vec<FocusDay>, CoreError> {
    let start = parse_date(from)?;
    let mut out = Vec::new();
    for offset in 0..days.min(62) {
        let date = (start + Days::new(u64::from(offset))).to_string();
        let minutes: u32 = conn.query_row(
            "SELECT COALESCE(SUM(minutes), 0) FROM focus_sessions WHERE local_date = ?1",
            [&date],
            |r| r.get(0),
        )?;
        out.push(FocusDay { date, minutes });
    }
    Ok(out)
}

/// The sessions of one day, oldest first.
pub fn on_day(conn: &Connection, date: &str) -> Result<Vec<FocusSession>, CoreError> {
    parse_date(date)?;
    let mut stmt = conn.prepare(
        "SELECT id, title, start_minutes, minutes FROM focus_sessions WHERE local_date = ?1
         ORDER BY start_minutes, rowid",
    )?;
    let rows = stmt
        .query_map([date], |r| {
            Ok(FocusSession {
                id: r.get(0)?,
                title: r.get(1)?,
                start_minutes: r.get(2)?,
                minutes: r.get(3)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(rows)
}

pub fn delete(conn: &Connection, id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM focus_sessions WHERE id = ?1", [id])?;
    Ok(())
}
