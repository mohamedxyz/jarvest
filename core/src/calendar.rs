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
    if let Some(until) = &event.until_date {
        if parse_date(until)? < parse_date(&event.date)? {
            return Err(invalid("The end date can't be before the start date"));
        }
        if event.repeat == EventRepeat::None {
            return Err(invalid("Only a repeating event can have an end date"));
        }
    }
    if event.remind_minutes.is_some_and(|m| m > DAY_MINUTES) {
        return Err(invalid("A reminder can be at most a day before"));
    }
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
        "INSERT INTO events (id, title, start_date, start_minutes, minutes, repeat, weekdays, until_date, remind_minutes)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            id,
            title,
            event.date,
            event.start_minutes,
            event.minutes,
            repeat_sql(event.repeat),
            mask,
            event.until_date,
            event.remind_minutes
        ],
    )?;
    Ok(id)
}

pub fn update_event(conn: &Connection, id: &str, event: &NewEvent) -> Result<(), CoreError> {
    let (title, mask) = check_event(event)?;
    let changed = conn.execute(
        "UPDATE events SET title = ?2, start_date = ?3, start_minutes = ?4, minutes = ?5,
                           repeat = ?6, weekdays = ?7, until_date = ?8, remind_minutes = ?9
         WHERE id = ?1",
        params![
            id,
            title,
            event.date,
            event.start_minutes,
            event.minutes,
            repeat_sql(event.repeat),
            mask,
            event.until_date,
            event.remind_minutes
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
            "SELECT id, title, start_date, start_minutes, minutes, repeat, weekdays, until_date, remind_minutes
             FROM events WHERE id = ?1",
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
                    until_date: r.get(7)?,
                    remind_minutes: r.get(8)?,
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

/// Events that happen on `day`: the start day, or a repeat that is not ended or skipped.
fn events_on(conn: &Connection, day: chrono::NaiveDate) -> Result<Vec<CalendarEvent>, CoreError> {
    let date = day.to_string();
    let mut stmt = conn.prepare(
        "SELECT id, title, start_date, start_minutes, minutes, repeat, weekdays, until_date, remind_minutes
         FROM events
         WHERE start_date <= ?1 AND (until_date IS NULL OR until_date >= ?1)
           AND NOT EXISTS (SELECT 1 FROM event_skips k WHERE k.event_id = events.id AND k.skip_date = ?1)
         ORDER BY start_minutes, rowid",
    )?;
    let rows = stmt.query_map([&date], |r| {
        Ok(CalendarEvent {
            id: r.get(0)?,
            title: r.get(1)?,
            date: r.get(2)?,
            start_minutes: r.get(3)?,
            minutes: r.get(4)?,
            repeat: repeat_from(&r.get::<_, String>(5)?),
            weekdays: r.get(6)?,
            until_date: r.get(7)?,
            remind_minutes: r.get(8)?,
        })
    })?;
    let mut out = Vec::new();
    for row in rows {
        let e = row?;
        let occurs = match e.repeat {
            EventRepeat::Daily => true,
            EventRepeat::Weekly => match e.weekdays {
                Some(mask) => is_scheduled(Some(mask), day),
                // The weekday the event started on.
                None => parse_date(&e.date)?.weekday() == day.weekday(),
            },
            EventRepeat::None => e.date == date,
        };
        if occurs {
            out.push(e);
        }
    }
    Ok(out)
}

/// Skips one day of a repeating event ("delete only this one").
pub fn skip_event_day(conn: &Connection, id: &str, date: &str) -> Result<(), CoreError> {
    parse_date(date)?;
    let repeat: Option<String> = conn
        .query_row("SELECT repeat FROM events WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .optional()?;
    match repeat.as_deref() {
        None => Err(invalid("That event no longer exists")),
        Some("none") => Err(invalid("Only a repeating event has days to skip")),
        Some(_) => {
            conn.execute(
                "INSERT OR IGNORE INTO event_skips (event_id, skip_date) VALUES (?1, ?2)",
                params![id, date],
            )?;
            Ok(())
        }
    }
}

/// Every reminder due on the days from `from` for `days` days, soonest first. The app sets one alarm per row.
pub fn event_reminders(
    conn: &Connection,
    from: &str,
    days: u32,
) -> Result<Vec<EventReminder>, CoreError> {
    let start = parse_date(from)?;
    let mut out = Vec::new();
    for offset in 0..days.min(14) {
        let day = start + chrono::Days::new(u64::from(offset));
        for e in events_on(conn, day)? {
            if let Some(remind) = e.remind_minutes {
                out.push(EventReminder {
                    event_id: e.id,
                    title: e.title,
                    date: day.to_string(),
                    start_minutes: e.start_minutes,
                    remind_minutes: remind,
                });
            }
        }
    }
    out.sort_by_key(|r| {
        (
            r.date.clone(),
            r.start_minutes.saturating_sub(r.remind_minutes),
        )
    });
    Ok(out)
}

/// How many blocks are planned on each day from `first` for `days` days, for the month overview.
pub fn month_marks(conn: &Connection, first: &str, days: u32) -> Result<Vec<DayMark>, CoreError> {
    let start = parse_date(first)?;
    let mut out = Vec::new();
    for offset in 0..days.min(42) {
        let date = (start + chrono::Days::new(u64::from(offset))).to_string();
        let count = day(conn, &date)?.len() as u32;
        out.push(DayMark { date, count });
    }
    Ok(out)
}

/// Everything planned on `date`: events, tasks due that day, and goals planned that day.
/// Timed blocks come first in time order, then the "anytime" ones in the order they were made.
pub fn day(conn: &Connection, date: &str) -> Result<Vec<CalendarBlock>, CoreError> {
    let day = parse_date(date)?;
    let mut blocks = Vec::new();

    for e in events_on(conn, day)? {
        blocks.push(CalendarBlock {
            kind: CalendarKind::Event,
            id: e.id,
            title: e.title,
            start_minutes: Some(e.start_minutes),
            minutes: e.minutes,
            done: false,
            icon: None,
            repeating: e.repeat != EventRepeat::None,
        });
    }

    let mut stmt = conn.prepare(
        // A milestone's task is named with its goal ("Read: 100"), so two "100"s can be told apart.
        "SELECT t.id,
                CASE WHEN t.milestone_id IS NOT NULL AND gm.title IS NOT NULL
                     THEN gm.title || ': ' || t.title ELSE t.title END,
                t.due_time, t.duration, t.done_date IS NOT NULL, t.icon, t.repeat IS NOT NULL
         FROM tasks t LEFT JOIN goals gm ON gm.id = t.goal_id
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
