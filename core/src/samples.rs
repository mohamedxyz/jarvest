//! Icons, sample data, and template bundles (first-run helpers).
//!
//! Sample data is flagged with `sample = 1` so it can be cleared (or kept) in one
//! tap. Its history is written straight into `entries`, without going through the
//! reward code, so samples never roll for a treat and never touch the reward
//! counters. Clearing deletes the flagged rows; rewards and completions hang off
//! the goal with `ON DELETE CASCADE`, so they disappear with it.

use chrono::Days;
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::{clean_optional, create_goal, invalid, new_id};
use crate::model::*;
use crate::tasks::create_task;

/// Most characters an icon may have (one emoji can be several characters).
const MAX_ICON_CHARS: usize = 8;

fn clean_icon(icon: Option<String>) -> Result<Option<String>, CoreError> {
    let icon = clean_optional(icon);
    match icon {
        Some(i) if i.chars().count() > MAX_ICON_CHARS => {
            Err(invalid("An icon can have at most 8 characters"))
        }
        other => Ok(other),
    }
}

/// Sets or clears (with `None`) the icon of a goal.
pub fn set_goal_icon(
    conn: &Connection,
    goal_id: &str,
    icon: Option<String>,
) -> Result<(), CoreError> {
    let changed = conn.execute(
        "UPDATE goals SET icon = ?2 WHERE id = ?1",
        params![goal_id, clean_icon(icon)?],
    )?;
    if changed == 0 {
        return Err(invalid("That goal no longer exists"));
    }
    Ok(())
}

/// Sets or clears (with `None`) the icon of a task.
pub fn set_task_icon(
    conn: &Connection,
    task_id: &str,
    icon: Option<String>,
) -> Result<(), CoreError> {
    let changed = conn.execute(
        "UPDATE tasks SET icon = ?2 WHERE id = ?1",
        params![task_id, clean_icon(icon)?],
    )?;
    if changed == 0 {
        return Err(invalid("That task no longer exists"));
    }
    Ok(())
}

pub fn has_sample_data(conn: &Connection) -> Result<bool, CoreError> {
    Ok(conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM goals WHERE sample = 1)
             OR EXISTS (SELECT 1 FROM tasks WHERE sample = 1)",
        [],
        |r| r.get(0),
    )?)
}

fn day_text(today: chrono::NaiveDate, days_back: u64) -> String {
    (today - Days::new(days_back)).to_string()
}

/// Writes one past entry directly (no reward code runs).
fn add_past_entry(
    conn: &Connection,
    goal_id: &str,
    date: &str,
    amount: f64,
    single: bool,
) -> Result<(), CoreError> {
    conn.execute(
        "INSERT INTO entries (id, goal_id, local_date, amount, single_per_day, seq)
         VALUES (?1, ?2, ?3, ?4, ?5, (SELECT COALESCE(MAX(seq), 0) + 1 FROM entries))",
        params![new_id(), goal_id, date, amount, single],
    )?;
    Ok(())
}

fn new_goal(title: &str, kind: GoalKind) -> NewGoal {
    NewGoal {
        title: title.into(),
        notes: String::new(),
        kind,
        category: None,
        period: None,
        period_target: None,
        total_target: None,
        unit: None,
        deadline: None,
        milestones: vec![],
        weekdays: None,
    }
}

fn new_task(title: &str) -> NewTask {
    NewTask {
        title: title.into(),
        notes: String::new(),
        due: None,
        priority: Priority::Med,
        tag: None,
        repeat: None,
        items: vec![],
        goal_id: None,
    }
}

/// Creates two sample goals with a week of history and two sample tasks.
/// Does nothing if sample data is already there.
pub fn create_sample_data(conn: &mut Connection, today: &str) -> Result<(), CoreError> {
    let today_date = parse_date(today)?;
    if has_sample_data(conn)? {
        return Ok(());
    }
    // The goals start a week ago so the history below is valid.
    let start = day_text(today_date, 7);

    let walk = create_goal(
        conn,
        NewGoal {
            period: Some(Period::Day),
            period_target: Some(1.0),
            ..new_goal("Walk 10 minutes", GoalKind::Habit)
        },
        &start,
    )?;
    let read = create_goal(
        conn,
        NewGoal {
            total_target: Some(300.0),
            unit: Some("pages".into()),
            ..new_goal("Read 300 pages", GoalKind::Numeric)
        },
        &start,
    )?;

    // Walked on most days of the last week, but not today (so it is left to tick).
    for back in [1u64, 2, 3, 5, 6] {
        add_past_entry(conn, &walk, &day_text(today_date, back), 1.0, true)?;
    }
    for (back, pages) in [(2u64, 20.0), (4, 35.0), (6, 25.0)] {
        add_past_entry(conn, &read, &day_text(today_date, back), pages, false)?;
    }

    let welcome = create_task(
        conn,
        NewTask {
            due: Some(today.into()),
            items: vec!["Tick me".into(), "Swipe or tap for more".into()],
            ..new_task("Try ticking this task")
        },
        today,
    )?;
    let later = create_task(
        conn,
        NewTask {
            due: Some(day_text(today_date, 0)),
            priority: Priority::High,
            tag: Some("sample".into()),
            ..new_task("Clear the sample data when ready")
        },
        today,
    )?;

    for id in [&walk, &read] {
        conn.execute("UPDATE goals SET sample = 1 WHERE id = ?1", params![id])?;
    }
    for id in [&welcome, &later] {
        conn.execute("UPDATE tasks SET sample = 1 WHERE id = ?1", params![id])?;
    }
    Ok(())
}

/// Removes every flagged goal and task, with their history and any rewards earned.
/// Returns how many goals and tasks were removed.
pub fn clear_sample_data(conn: &mut Connection) -> Result<u32, CoreError> {
    let tx = conn.transaction()?;
    // Tasks first: a milestone's task also goes when its goal goes (cascade).
    let tasks = tx.execute("DELETE FROM tasks WHERE sample = 1", [])?;
    let goals = tx.execute("DELETE FROM goals WHERE sample = 1", [])?;
    tx.commit()?;
    Ok((tasks + goals) as u32)
}

/// Keeps the sample items as real ones by removing the flag.
pub fn keep_sample_data(conn: &Connection) -> Result<(), CoreError> {
    conn.execute("UPDATE goals SET sample = 0 WHERE sample = 1", [])?;
    conn.execute("UPDATE tasks SET sample = 0 WHERE sample = 1", [])?;
    Ok(())
}

/// Ready-made bundles: `get_fit`, `learn_skill`, `school_year`.
/// Returns the number of goals and tasks created.
pub fn create_template(conn: &mut Connection, key: &str, today: &str) -> Result<u32, CoreError> {
    let date = parse_date(today)?;
    let week_ahead = (date + Days::new(7)).to_string();
    let daily = |title: &str, icon: &str| {
        (
            NewGoal {
                period: Some(Period::Day),
                period_target: Some(1.0),
                ..new_goal(title, GoalKind::Habit)
            },
            icon.to_string(),
        )
    };
    let weekly = |title: &str, times: f64, icon: &str| {
        (
            NewGoal {
                period: Some(Period::Week),
                period_target: Some(times),
                ..new_goal(title, GoalKind::Habit)
            },
            icon.to_string(),
        )
    };
    let (goals, tasks): (Vec<(NewGoal, String)>, Vec<&str>) = match key {
        "get_fit" => (
            vec![
                weekly("Work out", 3.0, "🏃"),
                daily("Drink 2 litres of water", "💧"),
            ],
            vec!["Pick a workout plan", "Buy water bottle"],
        ),
        "learn_skill" => (
            vec![
                daily("Practise for 20 minutes", "🎯"),
                (
                    NewGoal {
                        milestones: vec![
                            "Finish the basics".into(),
                            "Build a first project".into(),
                        ],
                        ..new_goal("Reach my first milestone", GoalKind::YesNo)
                    },
                    "🏁".to_string(),
                ),
            ],
            vec!["Choose a course or book", "Set up a place to practise"],
        ),
        "school_year" => (
            vec![
                weekly("Study session", 4.0, "📚"),
                (
                    NewGoal {
                        deadline: Some(week_ahead),
                        ..new_goal("Hand in the first assignment", GoalKind::YesNo)
                    },
                    "📝".to_string(),
                ),
            ],
            vec![
                "Get the timetable",
                "Buy notebooks and pens",
                "Plan the study week",
            ],
        ),
        _ => return Err(invalid("Unknown template")),
    };
    let count = (goals.len() + tasks.len()) as u32;
    for (goal, icon) in goals {
        let id = create_goal(conn, goal, today)?;
        set_goal_icon(conn, &id, Some(icon))?;
    }
    for title in tasks {
        create_task(conn, new_task(title), today)?;
    }
    Ok(count)
}
