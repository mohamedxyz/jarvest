//! The weekly review: a look back, a clean-up, and up to three things to focus on this week.
//! It only plans. It gives no XP and changes no score, streak or reward. `today` comes from the caller.

use chrono::NaiveDate;
use rusqlite::{Connection, OptionalExtension, params};

use crate::CoreError;
use crate::dates::{self, parse_date};
use crate::goals::invalid;
use crate::model::*;

const MAX_FOCUS: usize = 3;
const MAX_NOTE_CHARS: usize = 2000;

/// The first day of the week `today` is in, using the week start chosen in Settings.
fn this_week(conn: &Connection, today: &str) -> Result<NaiveDate, CoreError> {
    Ok(dates::period_start_ws(
        parse_date(today)?,
        Period::Week,
        dates::week_start(conn),
    ))
}

/// The week to review, if the review is due: it is a new week, the review was not finished or dismissed
/// yet, and there is some history to look back on. Returns the first day of the new week.
pub fn due(conn: &Connection, today: &str) -> Result<Option<String>, CoreError> {
    let week = this_week(conn, today)?.to_string();
    let done: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM week_reviews WHERE week_start = ?1)",
        [&week],
        |r| r.get(0),
    )?;
    if done {
        return Ok(None);
    }
    let history: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM day_scores WHERE local_date < ?1)
             OR EXISTS (SELECT 1 FROM goals WHERE created_date < ?1 AND deleted_date IS NULL)",
        [&week],
        |r| r.get(0),
    )?;
    Ok(history.then_some(week))
}

/// "Not now": the review is not offered again this week, and nothing is lost.
pub fn dismiss(conn: &Connection, today: &str) -> Result<(), CoreError> {
    let week = this_week(conn, today)?.to_string();
    conn.execute(
        "INSERT INTO week_reviews (week_start, dismissed_date) VALUES (?1, ?2)
         ON CONFLICT (week_start) DO NOTHING",
        params![week, today],
    )?;
    Ok(())
}

/// Checks that an item is a live goal or task that can be a focus.
fn check_pick(conn: &Connection, pick: &FocusPick) -> Result<(), CoreError> {
    let ok: bool = match pick.kind {
        FocusKind::Goal => conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM goals WHERE id = ?1 AND deleted_date IS NULL AND archived = 0)",
            [&pick.item_id],
            |r| r.get(0),
        )?,
        FocusKind::Task => conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ?1 AND deleted_date IS NULL)",
            [&pick.item_id],
            |r| r.get(0),
        )?,
    };
    if ok {
        Ok(())
    } else {
        Err(invalid("That goal or task is no longer there"))
    }
}

fn save_focus(conn: &Connection, week: &str, picks: &[FocusPick]) -> Result<(), CoreError> {
    if picks.len() > MAX_FOCUS {
        return Err(invalid("Pick at most three things to focus on"));
    }
    for (i, pick) in picks.iter().enumerate() {
        if picks[..i].iter().any(|p| p == pick) {
            return Err(invalid("Each focus can only be picked once"));
        }
        check_pick(conn, pick)?;
    }
    conn.execute("DELETE FROM week_focus WHERE week_start = ?1", [week])?;
    for (i, pick) in picks.iter().enumerate() {
        conn.execute(
            "INSERT INTO week_focus (week_start, position, kind, item_id) VALUES (?1, ?2, ?3, ?4)",
            params![
                week,
                (i + 1) as i64,
                match pick.kind {
                    FocusKind::Goal => "goal",
                    FocusKind::Task => "task",
                },
                pick.item_id
            ],
        )?;
    }
    Ok(())
}

/// Finishes the review: saves the note and up to three things to focus on this week.
pub fn finish(
    conn: &mut Connection,
    today: &str,
    note: &str,
    picks: &[FocusPick],
) -> Result<(), CoreError> {
    let week = this_week(conn, today)?.to_string();
    let note = note.trim();
    if note.chars().count() > MAX_NOTE_CHARS {
        return Err(invalid("A note can be at most 2000 characters"));
    }
    let tx = conn.transaction()?;
    save_focus(&tx, &week, picks)?;
    tx.execute(
        "INSERT INTO week_reviews (week_start, note, finished_date) VALUES (?1, ?2, ?3)
         ON CONFLICT (week_start) DO UPDATE SET note = excluded.note, finished_date = excluded.finished_date",
        params![week, note, today],
    )?;
    tx.commit()?;
    Ok(())
}

/// Changes this week's focus later, without a review.
pub fn set_focus(conn: &mut Connection, today: &str, picks: &[FocusPick]) -> Result<(), CoreError> {
    let week = this_week(conn, today)?.to_string();
    let tx = conn.transaction()?;
    save_focus(&tx, &week, picks)?;
    tx.commit()?;
    Ok(())
}

/// This week's focus items. An item that was deleted or trashed since drops off the list.
pub fn focus(conn: &Connection, today: &str) -> Result<Vec<WeekFocusItem>, CoreError> {
    let week = this_week(conn, today)?.to_string();
    let mut stmt = conn
        .prepare("SELECT kind, item_id FROM week_focus WHERE week_start = ?1 ORDER BY position")?;
    let rows: Vec<(String, String)> = stmt
        .query_map([&week], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let mut items = Vec::new();
    for (kind, id) in rows {
        if kind == "goal" {
            let goal: Option<String> = conn
                .query_row(
                    "SELECT title FROM goals WHERE id = ?1 AND deleted_date IS NULL AND archived = 0",
                    [&id],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(title) = goal {
                let done = crate::goals::get_goal(conn, &id)?.done;
                items.push(WeekFocusItem {
                    kind: FocusKind::Goal,
                    item_id: id,
                    title,
                    done,
                });
            }
        } else {
            let task: Option<(String, bool)> = conn
                .query_row(
                    "SELECT title, done_date IS NOT NULL FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
                    [&id],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            if let Some((title, done)) = task {
                items.push(WeekFocusItem {
                    kind: FocusKind::Task,
                    item_id: id,
                    title,
                    done,
                });
            }
        }
    }
    Ok(items)
}
