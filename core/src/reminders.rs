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
         WHERE g.archived = 0 AND g.deleted_date IS NULL ORDER BY r.minutes, g.title COLLATE NOCASE",
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
        None => {
            // Open tasks due today or earlier count too, with the late ones called out.
            let tasks = crate::tasks::due_tasks(conn, today)?;
            if due.is_empty() && tasks.is_empty() {
                return Ok(None);
            }
            let names: Vec<&str> = due
                .iter()
                .map(|item| item.title.as_str())
                .chain(tasks.iter().map(|task| task.title.as_str()))
                .collect();
            let shown: Vec<&str> = names.iter().take(4).copied().collect();
            let more = names.len().saturating_sub(shown.len());
            let mut text = shown.join(", ");
            if more > 0 {
                text.push_str(&format!(" and {more} more"));
            }
            let plural = |n: usize, one: &str| {
                if n == 1 {
                    format!("1 {one}")
                } else {
                    format!("{n} {one}s")
                }
            };
            let mut counts = Vec::new();
            if !due.is_empty() {
                counts.push(plural(due.len(), "goal"));
            }
            if !tasks.is_empty() {
                let late = tasks.iter().filter(|t| t.overdue).count();
                let mut part = plural(tasks.len(), "task");
                if late > 0 {
                    part.push_str(&format!(" ({late} overdue)"));
                }
                counts.push(part);
            }
            Some(format!("{} left today: {text}", counts.join(" and ")))
        }
    })
}

const EVENING_KEY: &str = "evening_reminder_minutes";

/// The optional second reminder of the day, for a last check-in. Its time is stored
/// like the daily one, and a damaged value means "off".
pub fn evening_reminder(conn: &Connection) -> Result<Option<u16>, CoreError> {
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key = ?1",
            params![EVENING_KEY],
            |r| r.get(0),
        )
        .optional()?;
    Ok(value
        .and_then(|v| v.parse::<u16>().ok())
        .filter(|m| *m <= LAST_MINUTE))
}

pub fn set_evening_reminder(conn: &Connection, minutes: Option<u16>) -> Result<(), CoreError> {
    match minutes {
        Some(minutes) => {
            check(minutes)?;
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![EVENING_KEY, minutes.to_string()],
            )?;
        }
        None => {
            conn.execute("DELETE FROM meta WHERE key = ?1", params![EVENING_KEY])?;
        }
    }
    Ok(())
}

/// "2 goals and 1 task left today", or `None` when nothing is left (so no notification).
/// Goals in the trash never count, because `today_items` skips them.
pub fn evening_text(conn: &Connection, today: &str) -> Result<Option<String>, CoreError> {
    let goals = today_items(conn, today)?
        .into_iter()
        .filter(|item| !item.complete)
        .count();
    let tasks = crate::tasks::due_tasks(conn, today)?.len();
    let plural = |n: usize, one: &str| {
        if n == 1 {
            format!("1 {one}")
        } else {
            format!("{n} {one}s")
        }
    };
    Ok(match (goals, tasks) {
        (0, 0) => None,
        (g, 0) => Some(format!("{} left today", plural(g, "goal"))),
        (0, t) => Some(format!("{} left today", plural(t, "task"))),
        (g, t) => Some(format!(
            "{} and {} left today",
            plural(g, "goal"),
            plural(t, "task")
        )),
    })
}
