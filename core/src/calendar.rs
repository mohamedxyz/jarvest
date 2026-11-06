//! The calendar: events, time blocks for tasks and goals, and one day's blocks.
//! Events and blocks are only for planning. They never change the score, XP, streaks or rewards.
//! `date` always comes from the caller, so nothing here reads the clock.

use chrono::Datelike;
use rusqlite::{Connection, OptionalExtension, params};

use crate::CoreError;
use crate::dates::{is_scheduled, parse_date};
use crate::goals::{clean_title, invalid, new_id, today_items};
use crate::model::*;

/// The length shown for a task or goal that has none.
const DEFAULT_MINUTES: u32 = 30;
const MIN_MINUTES: u32 = 5;
const DAY_MINUTES: u32 = 24 * 60;

fn repeat_sql(repeat: EventRepeat) -> &'static str {
    match repeat {
        EventRepeat::None => "none",
        EventRepeat::Daily => "daily",
        EventRepeat::Weekly => "weekly",
    }
}

fn repeat_from(text: &str) -> EventRepeat {
    match text {
        "daily" => EventRepeat::Daily,
        "weekly" => EventRepeat::Weekly,
        _ => EventRepeat::None,
    }
}

fn check_times(start: u32, minutes: u32) -> Result<(), CoreError> {
    if minutes < MIN_MINUTES {
        return Err(invalid("A block must be at least 5 minutes long"));
    }
    if start + minutes > DAY_MINUTES {
        return Err(invalid("A block must end before midnight"));
    }
    Ok(())
}

fn check_event(event: &NewEvent) -> Result<(String, Option<u8>), CoreError> {
    let title = clean_title(&event.title)?;
    parse_date(&event.date)?;
    check_times(event.start_minutes, event.minutes)?;
    let mask = match (event.repeat, event.weekdays) {
        (EventRepeat::Weekly, Some(0)) => {
            return Err(invalid("Pick at least one weekday"));
        }
        (EventRepeat::Weekly, Some(mask)) if mask < 128 && mask != 127 => Some(mask),
        // All seven days is stored as nothing, like goals.
        (EventRepeat::Weekly, Some(127) | None) | (EventRepeat::Daily | EventRepeat::None, _) => {
            None
        }
        (EventRepeat::Weekly, Some(_)) => return Err(invalid("Invalid weekdays")),
    };
    Ok((title, mask))
}

pub fn create_event(conn: &Connection, event: &NewEvent) -> Result<String, CoreError> {
    let (title, mask) = check_event(event)?;
    let id = new_id();
    conn.execute(
        "INSERT INTO events (id, title, start_date, start_minutes, minutes, repeat, weekdays)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            id,
            title,
            event.date,
            event.start_minutes,
            event.minutes,
            repeat_sql(event.repeat),
            mask
        ],
    )?;
    Ok(id)
}

pub fn update_event(conn: &Connection, id: &str, event: &NewEvent) -> Result<(), CoreError> {
    let (title, mask) = check_event(event)?;
    let changed = conn.execute(
        "UPDATE events SET title = ?2, start_date = ?3, start_minutes = ?4, minutes = ?5,
                           repeat = ?6, weekdays = ?7
         WHERE id = ?1",
        params![
            id,
            title,
            event.date,
            event.start_minutes,
            event.minutes,
            repeat_sql(event.repeat),
            mask
        ],
    )?;
    if changed == 0 {
        return Err(invalid("That event no longer exists"));
    }
    Ok(())
}

pub fn delete_event(conn: &Connection, id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM events WHERE id = ?1", [id])?;
    Ok(())
}

pub fn get_event(conn: &Connection, id: &str) -> Result<Option<CalendarEvent>, CoreError> {
    Ok(conn
        .query_row(
            "SELECT id, title, start_date, start_minutes, minutes, repeat, weekdays FROM events WHERE id = ?1",
            [id],
            |r| {
                Ok(CalendarEvent {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    date: r.get(2)?,
                    start_minutes: r.get(3)?,
                    minutes: r.get(4)?,
                    repeat: repeat_from(&r.get::<_, String>(5)?),
                    weekdays: r.get(6)?,
                })
            },
        )
        .optional()?)
}

/// Sets or clears a task's start time and length. A start time needs a due day.
pub fn set_task_time(
    conn: &Connection,
    task_id: &str,
    start: Option<u32>,
    minutes: Option<u32>,
) -> Result<(), CoreError> {
    let due: Option<Option<String>> = conn
        .query_row(
            "SELECT due FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
            [task_id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(due) = due else {
        return Err(invalid("That task no longer exists"));
    };
    if start.is_some() && due.is_none() {
        return Err(invalid("Set a due date before choosing a time"));
    }
    if let Some(minutes) = minutes {
        check_times(start.unwrap_or(0), minutes)?;
    }
    if let Some(start) = start {
        check_times(start, minutes.unwrap_or(DEFAULT_MINUTES))?;
    }
    conn.execute(
        "UPDATE tasks SET due_time = ?2, duration = ?3 WHERE id = ?1",
        params![task_id, start, minutes],
    )?;
    Ok(())
}

/// Sets or clears a goal's time block.
pub fn set_goal_block(
    conn: &Connection,
    goal_id: &str,
    start: Option<u32>,
    minutes: Option<u32>,
) -> Result<(), CoreError> {
    if let Some(start) = start {
        check_times(start, minutes.unwrap_or(DEFAULT_MINUTES))?;
    }
    if start.is_none() && minutes.is_some() {
        return Err(invalid("Choose a start time for the block"));
    }
    let changed = conn.execute(
        "UPDATE goals SET block_start = ?2, block_minutes = ?3 WHERE id = ?1",
        params![goal_id, start, minutes],
    )?;
    if changed == 0 {
        return Err(invalid("That goal no longer exists"));
    }
    Ok(())
}

pub fn goal_block(conn: &Connection, goal_id: &str) -> Result<GoalBlock, CoreError> {
    let (start, minutes): (Option<u32>, Option<u32>) = conn
        .query_row(
            "SELECT block_start, block_minutes FROM goals WHERE id = ?1",
            [goal_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?
        .unwrap_or((None, None));
    Ok(GoalBlock {
        start_minutes: start,
        minutes: minutes.unwrap_or(DEFAULT_MINUTES),
    })
}

/// Everything planned on `date`: events, tasks due that day, and goals planned that day.
/// Timed blocks come first in time order, then the "anytime" ones in the order they were made.
pub fn day(conn: &Connection, date: &str) -> Result<Vec<CalendarBlock>, CoreError> {
    let day = parse_date(date)?;
    let mut blocks = Vec::new();

    let mut stmt = conn.prepare(
        "SELECT id, title, start_date, start_minutes, minutes, repeat, weekdays FROM events
         WHERE start_date <= ?1 ORDER BY start_minutes, rowid",
    )?;
    let rows = stmt.query_map([date], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, u32>(3)?,
            r.get::<_, u32>(4)?,
            r.get::<_, String>(5)?,
            r.get::<_, Option<u8>>(6)?,
        ))
    })?;
    for row in rows {
        let (id, title, start_date, start, minutes, repeat, weekdays) = row?;
        let occurs = match repeat.as_str() {
            "daily" => true,
            "weekly" => match weekdays {
                Some(mask) => is_scheduled(Some(mask), day),
                // The weekday the event started on.
                None => parse_date(&start_date)?.weekday() == day.weekday(),
            },
            _ => start_date == date,
        };
        if occurs {
            blocks.push(CalendarBlock {
                kind: CalendarKind::Event,
                id,
                title,
                start_minutes: Some(start),
                minutes,
                done: false,
                icon: None,
                repeating: repeat != "none",
            });
        }
    }

    let mut stmt = conn.prepare(
        "SELECT t.id, t.title, t.due_time, t.duration, t.done_date IS NOT NULL, t.icon, t.repeat IS NOT NULL
         FROM tasks t
         WHERE t.due = ?1 AND t.deleted_date IS NULL AND t.archived_date IS NULL
           AND NOT (t.milestone_id IS NOT NULL AND EXISTS
                (SELECT 1 FROM goals g WHERE g.id = t.goal_id AND g.deleted_date IS NOT NULL))
         ORDER BY t.due_time IS NULL, t.due_time, t.rowid",
    )?;
    let rows = stmt.query_map([date], |r| {
        Ok(CalendarBlock {
            kind: CalendarKind::Task,
            id: r.get(0)?,
            title: r.get(1)?,
            start_minutes: r.get(2)?,
            minutes: r.get::<_, Option<u32>>(3)?.unwrap_or(DEFAULT_MINUTES),
            done: r.get(4)?,
            icon: r.get(5)?,
            repeating: r.get(6)?,
        })
    })?;
    for row in rows {
        blocks.push(row?);
    }

    for item in today_items(conn, date)? {
        let (start, minutes): (Option<u32>, Option<u32>) = conn.query_row(
            "SELECT block_start, block_minutes FROM goals WHERE id = ?1",
            [&item.goal_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        blocks.push(CalendarBlock {
            kind: CalendarKind::Goal,
            id: item.goal_id,
            title: item.title,
            start_minutes: start,
            minutes: minutes.unwrap_or(DEFAULT_MINUTES),
            done: item.complete,
            icon: item.icon,
            repeating: true,
        });
    }

    // Stable sort: timed blocks by start, then the ones without a time, each keeping its order.
    blocks.sort_by_key(|b| (b.start_minutes.is_none(), b.start_minutes.unwrap_or(0)));
    Ok(blocks)
}
