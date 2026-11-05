//! Reminder times and the text of reminder notifications. The phone schedules the
//! alarms; this module only stores times and decides what a reminder should say.

use rusqlite::{Connection, OptionalExtension, params};

use crate::CoreError;
use crate::goals::{get_goal, invalid, today_items};
use crate::model::Reminder;

const DAILY_KEY: &str = "daily_reminder_minutes";
const LAST_MINUTE: u16 = 24 * 60 - 1;

fn check(minutes: u16) -> Result<(), CoreError> {
    if minutes > LAST_MINUTE {
        return Err(invalid("A reminder time must be within one day"));
    }
    Ok(())
}

/// Sets, changes, or (with `None`) removes a goal's reminder.
pub fn set_goal_reminder(
    conn: &Connection,
    goal_id: &str,
    minutes: Option<u16>,
) -> Result<(), CoreError> {
    get_goal(conn, goal_id)?;
    match minutes {
        Some(minutes) => {
            check(minutes)?;
            conn.execute(
                "INSERT INTO reminders (goal_id, minutes) VALUES (?1, ?2)
                 ON CONFLICT (goal_id) DO UPDATE SET minutes = excluded.minutes",
                params![goal_id, minutes],
            )?;
        }
        None => {
            conn.execute("DELETE FROM reminders WHERE goal_id = ?1", params![goal_id])?;
        }
    }
    Ok(())
}

pub fn goal_reminder(conn: &Connection, goal_id: &str) -> Result<Option<u16>, CoreError> {
    Ok(conn
        .query_row(
            "SELECT minutes FROM reminders WHERE goal_id = ?1",
            params![goal_id],
            |r| r.get(0),
        )
        .optional()?)
}

/// Reminders for active goals, earliest first.
pub fn list_reminders(conn: &Connection) -> Result<Vec<Reminder>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT r.goal_id, g.title, r.minutes FROM reminders r JOIN goals g ON g.id = r.goal_id
         WHERE g.archived = 0 ORDER BY r.minutes, g.title COLLATE NOCASE",
    )?;
    let list = stmt
        .query_map([], |r| {
            Ok(Reminder {
                goal_id: r.get(0)?,
                title: r.get(1)?,
                minutes: r.get(2)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(list)
}

pub fn daily_reminder(conn: &Connection) -> Result<Option<u16>, CoreError> {
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key = ?1",
            params![DAILY_KEY],
            |r| r.get(0),
        )
        .optional()?;
    // A damaged value means no reminder rather than an error on every start.
    Ok(value
        .and_then(|v| v.parse::<u16>().ok())
        .filter(|m| *m <= LAST_MINUTE))
}

pub fn set_daily_reminder(conn: &Connection, minutes: Option<u16>) -> Result<(), CoreError> {
    match minutes {
        Some(minutes) => {
            check(minutes)?;
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![DAILY_KEY, minutes.to_string()],
            )?;
        }
        None => {
            conn.execute("DELETE FROM meta WHERE key = ?1", params![DAILY_KEY])?;
        }
    }
    Ok(())
}

/// The notification text, or `None` when there is nothing to remind about: the goal
/// is done, skipped, paused, or off today, or no goal is left for the daily summary.
pub fn reminder_text(
    conn: &Connection,
    goal_id: Option<&str>,
    today: &str,
) -> Result<Option<String>, CoreError> {
    let due: Vec<_> = today_items(conn, today)?
        .into_iter()
        .filter(|item| !item.complete)
        .collect();
    Ok(match goal_id {
        Some(id) => due
            .iter()
            .find(|item| item.goal_id == id)
            .map(|item| format!("Time for {}", item.title)),
        None if due.is_empty() => None,
        None => {
            let names: Vec<_> = due.iter().take(4).map(|item| item.title.as_str()).collect();
            let more = due.len().saturating_sub(names.len());
            let mut text = names.join(", ");
            if more > 0 {
                text.push_str(&format!(" and {more} more"));
            }
            Some(format!(
                "{} left today: {text}",
                if due.len() == 1 {
                    "1 goal".to_string()
                } else {
                    format!("{} goals", due.len())
                }
            ))
        }
    })
}
