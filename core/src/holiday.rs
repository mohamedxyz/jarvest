//! Holiday mode: one action that pauses every goal for a stretch of days.
//! It only adds skipped days (the same ones `pause_goal` adds), so streaks and rates already know what
//! to do with them. Tasks are not touched and still count in the day score.

use chrono::{Days, NaiveDate};
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::{invalid, new_id};
use crate::model::*;

/// Longest holiday that can be set in one go, like a pause.
const MAX_DAYS: i64 = 366;

/// Starts a holiday from `from` to `to`. Every active goal is skipped on those days, from the day it was
/// created. Goals made later are not part of it. Only skipped days the holiday really added are
/// remembered, so a day that was already skipped by hand stays skipped when the holiday ends.
pub fn start(
    conn: &mut Connection,
    from: &str,
    to: &str,
    today: &str,
) -> Result<String, CoreError> {
    let (start, end) = (parse_date(from)?, parse_date(to)?);
    let today = parse_date(today)?;
    if end < start {
        return Err(invalid(
            "The holiday must end on or after the day it starts",
        ));
    }
    if end < today {
        return Err(invalid("A holiday can't be over before it starts"));
    }
    if (end - start).num_days() >= MAX_DAYS {
        return Err(invalid("A holiday can last at most a year"));
    }
    let tx = conn.transaction()?;
    let overlaps: bool = tx.query_row(
        "SELECT EXISTS (SELECT 1 FROM holidays WHERE start_date <= ?2 AND end_date >= ?1)",
        params![from, to],
        |r| r.get(0),
    )?;
    if overlaps {
        return Err(invalid(
            "That is not free: another holiday covers some of those days",
        ));
    }
    let id = new_id();
    tx.execute(
        "INSERT INTO holidays (id, start_date, end_date) VALUES (?1, ?2, ?3)",
        params![id, from, to],
    )?;
    let goals: Vec<(String, String)> = {
        let mut stmt = tx.prepare(
            "SELECT id, created_date FROM goals WHERE deleted_date IS NULL AND archived = 0",
        )?;
        stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<Result<_, _>>()?
    };
    for (goal_id, created) in goals {
        let mut day = start.max(parse_date(&created)?);
        while day <= end {
            let added = tx.execute(
                "INSERT OR IGNORE INTO skips (goal_id, local_date) VALUES (?1, ?2)",
                params![goal_id, day.to_string()],
            )?;
            if added == 1 {
                tx.execute(
                    "INSERT INTO holiday_skips (holiday_id, goal_id, local_date) VALUES (?1, ?2, ?3)",
                    params![id, goal_id, day.to_string()],
                )?;
            }
            day = day + Days::new(1);
        }
    }
    tx.commit()?;
    Ok(id)
}

/// Ends a holiday. Days from `today` on stop being skipped. Days already past stay as they were, so
/// history never changes. A holiday that has not started yet is removed completely.
pub fn end(conn: &mut Connection, id: &str, today: &str) -> Result<(), CoreError> {
    let today_date = parse_date(today)?;
    let tx = conn.transaction()?;
    let start: String = tx
        .query_row("SELECT start_date FROM holidays WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .map_err(|_| invalid("That holiday no longer exists"))?;
    tx.execute(
        "DELETE FROM skips WHERE EXISTS (
             SELECT 1 FROM holiday_skips h
             WHERE h.holiday_id = ?1 AND h.goal_id = skips.goal_id
               AND h.local_date = skips.local_date AND h.local_date >= ?2)",
        params![id, today],
    )?;
    tx.execute(
        "DELETE FROM holiday_skips WHERE holiday_id = ?1 AND local_date >= ?2",
        params![id, today],
    )?;
    if parse_date(&start)? >= today_date {
        tx.execute("DELETE FROM holidays WHERE id = ?1", [id])?;
    } else {
        let yesterday = today_date - Days::new(1);
        tx.execute(
            "UPDATE holidays SET end_date = ?2 WHERE id = ?1",
            params![id, yesterday.to_string()],
        )?;
    }
    tx.commit()?;
    Ok(())
}

/// Holidays, newest first.
pub fn list(conn: &Connection, today: &str) -> Result<Vec<Holiday>, CoreError> {
    parse_date(today)?;
    let mut stmt = conn.prepare(
        "SELECT h.id, h.start_date, h.end_date,
                (SELECT COUNT(DISTINCT goal_id) FROM holiday_skips s WHERE s.holiday_id = h.id)
         FROM holidays h ORDER BY h.start_date DESC",
    )?;
    let rows = stmt
        .query_map([], |r| {
            let (start, end): (String, String) = (r.get(1)?, r.get(2)?);
            Ok(Holiday {
                id: r.get(0)?,
                active: start.as_str() <= today && today <= end.as_str(),
                start_date: start,
                end_date: end,
                goals_covered: r.get(3)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(rows)
}

/// Every holiday as a pair of days, for rules that treat those days as neutral.
pub fn ranges(conn: &Connection) -> Result<Vec<(NaiveDate, NaiveDate)>, CoreError> {
    let mut stmt = conn.prepare("SELECT start_date, end_date FROM holidays")?;
    let rows: Vec<(String, String)> = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    rows.into_iter()
        .map(|(a, b)| Ok((parse_date(&a)?, parse_date(&b)?)))
        .collect()
}
