//! Routines: an ordered list of goals done together, such as a morning routine.
//! A step is an ordinary goal, ticked through the same path as the Today screen, so a routine
//! never changes rewards, streaks or XP. Quit habits can't be steps, because they log slips.

use std::collections::HashMap;

use rusqlite::{Connection, params};

use crate::CoreError;
use crate::goals::{clean_title, invalid, new_id};
use crate::model::*;

const MAX_STEPS: usize = 12;
const MAX_ROUTINES: i64 = 20;

fn check_steps(conn: &Connection, goal_ids: &[String]) -> Result<(), CoreError> {
    if goal_ids.is_empty() || goal_ids.len() > MAX_STEPS {
        return Err(invalid("A routine needs 1 to 12 goals"));
    }
    for (i, id) in goal_ids.iter().enumerate() {
        if goal_ids[..i].contains(id) {
            return Err(invalid("A goal can only be in a routine once"));
        }
        let row: Option<(bool, bool)> = conn
            .query_row(
                "SELECT avoid, archived FROM goals WHERE id = ?1 AND deleted_date IS NULL",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .ok();
        match row {
            None => return Err(invalid("One of those goals is no longer there")),
            Some((true, _)) => return Err(invalid("A quit habit can't be a step in a routine")),
            Some((_, true)) => {
                return Err(invalid("An archived goal can't be a step in a routine"));
            }
            Some(_) => {}
        }
    }
    Ok(())
}

fn write_steps(conn: &Connection, id: &str, goal_ids: &[String]) -> Result<(), CoreError> {
    conn.execute("DELETE FROM routine_steps WHERE routine_id = ?1", [id])?;
    for (i, goal) in goal_ids.iter().enumerate() {
        conn.execute(
            "INSERT INTO routine_steps (routine_id, goal_id, position) VALUES (?1, ?2, ?3)",
            params![id, goal, i as i64],
        )?;
    }
    Ok(())
}

pub fn create(conn: &mut Connection, name: &str, goal_ids: &[String]) -> Result<String, CoreError> {
    let name = clean_title(name)?;
    let tx = conn.transaction()?;
    let count: i64 = tx.query_row("SELECT COUNT(*) FROM routines", [], |r| r.get(0))?;
    if count >= MAX_ROUTINES {
        return Err(invalid("You can have up to 20 routines"));
    }
    check_steps(&tx, goal_ids)?;
    let id = new_id();
    tx.execute(
        "INSERT INTO routines (id, name, position)
         VALUES (?1, ?2, (SELECT COALESCE(MAX(position), 0) + 1 FROM routines))",
        params![id, name],
    )?;
    write_steps(&tx, &id, goal_ids)?;
    tx.commit()?;
    Ok(id)
}

pub fn update(
    conn: &mut Connection,
    id: &str,
    name: &str,
    goal_ids: &[String],
) -> Result<(), CoreError> {
    let name = clean_title(name)?;
    let tx = conn.transaction()?;
    check_steps(&tx, goal_ids)?;
    let changed = tx.execute(
        "UPDATE routines SET name = ?2 WHERE id = ?1",
        params![id, name],
    )?;
    if changed == 0 {
        return Err(invalid("That routine no longer exists"));
    }
    write_steps(&tx, id, goal_ids)?;
    tx.commit()?;
    Ok(())
}

pub fn delete(conn: &Connection, id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM routines WHERE id = ?1", [id])?;
    Ok(())
}

/// Every routine, with how many of its steps are planned today and how many of those are done.
pub fn list(conn: &Connection, today: &str) -> Result<Vec<Routine>, CoreError> {
    let planned: HashMap<String, bool> = crate::goals::today_items(conn, today)?
        .into_iter()
        .map(|i| (i.goal_id, i.complete))
        .collect();
    let mut stmt = conn.prepare("SELECT id, name FROM routines ORDER BY position, rowid")?;
    let routines: Vec<(String, String)> = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let mut out = Vec::new();
    for (id, name) in routines {
        let mut stmt = conn.prepare(
            "SELECT s.goal_id FROM routine_steps s JOIN goals g ON g.id = s.goal_id
             WHERE s.routine_id = ?1 AND g.deleted_date IS NULL ORDER BY s.position",
        )?;
        let goal_ids: Vec<String> = stmt
            .query_map([&id], |r| r.get(0))?
            .collect::<Result<_, _>>()?;
        let steps_today = goal_ids.iter().filter(|g| planned.contains_key(*g)).count() as u32;
        let steps_done = goal_ids
            .iter()
            .filter(|g| planned.get(*g).copied().unwrap_or(false))
            .count() as u32;
        out.push(Routine {
            id,
            name,
            goal_ids,
            steps_today,
            steps_done,
        });
    }
    Ok(out)
}

/// The steps of a routine that are planned today, in order. Skipped, paused and trashed goals are left out.
pub fn items(conn: &Connection, id: &str, today: &str) -> Result<Vec<TodayItem>, CoreError> {
    let mut by_goal: HashMap<String, TodayItem> = crate::goals::today_items(conn, today)?
        .into_iter()
        .map(|i| (i.goal_id.clone(), i))
        .collect();
    let mut stmt =
        conn.prepare("SELECT goal_id FROM routine_steps WHERE routine_id = ?1 ORDER BY position")?;
    let order: Vec<String> = stmt
        .query_map([id], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    Ok(order
        .into_iter()
        .filter_map(|g| by_goal.remove(&g))
        .collect())
}
