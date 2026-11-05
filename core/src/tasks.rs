//! Tasks: to-do items with a due date, priority, tag, checklist and optional repeat.
//! A task may be linked to a goal, which only groups it there. Tasks never log a
//! goal, earn rewards, or change stats.

use chrono::{Datelike, Days, Months, NaiveDate, Weekday};
use rusqlite::{Connection, OptionalExtension, Row, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::{clean_optional, clean_title, invalid, new_id};
use crate::model::*;

/// Days a deleted task stays in the trash before `purge_trash` removes it.
pub const TRASH_DAYS: u64 = 30;

const TASK_COLUMNS: &str = "t.id, t.title, t.notes, t.due, t.priority, t.tag, t.repeat,
    t.created_date, t.done_date,
    (SELECT COUNT(*) FROM task_items i WHERE i.task_id = t.id AND i.done = 1),
    (SELECT COUNT(*) FROM task_items i WHERE i.task_id = t.id),
    (SELECT g.id FROM goals g WHERE g.id = t.goal_id AND g.deleted_date IS NULL),
    (SELECT g.title FROM goals g WHERE g.id = t.goal_id AND g.deleted_date IS NULL),
    t.milestone_id";

/// A milestone's task belongs to its goal, so it is hidden while the goal is in the
/// trash. An ordinary task linked to a trashed goal stays visible but shows as
/// unlinked (see `TASK_COLUMNS`); the link comes back when the goal is restored.
const VISIBLE: &str = "NOT (t.milestone_id IS NOT NULL AND EXISTS
    (SELECT 1 FROM goals g WHERE g.id = t.goal_id AND g.deleted_date IS NOT NULL))";

fn task_from_row(row: &Row, today: &str) -> rusqlite::Result<Task> {
    let due: Option<String> = row.get(3)?;
    let done_date: Option<String> = row.get(8)?;
    let done = done_date.is_some();
    // Dates are YYYY-MM-DD, so comparing the text compares the days.
    let overdue = !done && due.as_deref().is_some_and(|d| d < today);
    Ok(Task {
        id: row.get(0)?,
        title: row.get(1)?,
        notes: row.get(2)?,
        due,
        priority: row.get(4)?,
        tag: row.get(5)?,
        repeat: row.get(6)?,
        created_date: row.get(7)?,
        done_date,
        done,
        overdue,
        items_done: row.get(9)?,
        items_total: row.get(10)?,
        goal_id: row.get(11)?,
        goal_title: row.get(12)?,
        milestone_id: row.get(13)?,
    })
}

fn clean_tag(tag: Option<String>) -> Result<Option<String>, CoreError> {
    let tag = clean_optional(tag).map(|t| t.trim_start_matches('#').trim().to_string());
    match tag.filter(|t| !t.is_empty()) {
        Some(t) if t.chars().count() > 40 => Err(invalid("A tag can have at most 40 characters")),
        other => Ok(other),
    }
}

/// A task can only be linked to a goal that exists and is not archived.
fn check_goal(conn: &Connection, goal_id: &Option<String>) -> Result<(), CoreError> {
    let Some(goal_id) = goal_id else {
        return Ok(());
    };
    let usable: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM goals WHERE id = ?1 AND archived = 0 AND deleted_date IS NULL)",
        [goal_id],
        |r| r.get(0),
    )?;
    if usable {
        Ok(())
    } else {
        Err(invalid("Pick a goal that exists and isn't archived"))
    }
}

fn validate_due(due: &Option<String>) -> Result<(), CoreError> {
    if let Some(due) = due {
        parse_date(due)?;
    }
    Ok(())
}

/// The task that mirrors a milestone, so it also shows in the Tasks tab.
pub fn create_milestone_task(
    conn: &Connection,
    goal_id: &str,
    milestone_id: &str,
    title: &str,
    today: &str,
) -> Result<(), CoreError> {
    conn.execute(
        "INSERT INTO tasks (id, title, created_date, goal_id, milestone_id)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![new_id(), title, today, goal_id, milestone_id],
    )?;
    Ok(())
}

pub fn create_task(conn: &mut Connection, task: NewTask, today: &str) -> Result<String, CoreError> {
    parse_date(today)?;
    let title = clean_title(&task.title)?;
    validate_due(&task.due)?;
    let tag = clean_tag(task.tag)?;
    check_goal(conn, &task.goal_id)?;
    let items = task
        .items
        .iter()
        .map(|i| clean_title(i))
        .collect::<Result<Vec<_>, _>>()?;
    let id = new_id();
    let tx = conn.transaction()?;
    tx.execute(
        "INSERT INTO tasks (id, title, notes, due, priority, tag, repeat, created_date, goal_id)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            id,
            title,
            task.notes.trim(),
            task.due,
            task.priority.as_sql(),
            tag,
            task.repeat.map(Repeat::as_sql),
            today,
            task.goal_id
        ],
    )?;
    for (position, text) in items.iter().enumerate() {
        tx.execute(
            "INSERT INTO task_items (id, task_id, text, position) VALUES (?1, ?2, ?3, ?4)",
            params![new_id(), id, text, position as i64],
        )?;
    }
    tx.commit()?;
    Ok(id)
}

pub fn update_task(conn: &Connection, task_id: &str, edit: TaskEdit) -> Result<(), CoreError> {
    let title = clean_title(&edit.title)?;
    validate_due(&edit.due)?;
    let tag = clean_tag(edit.tag)?;
    // Keeping the link to a goal that was archived since is fine. Picking a new one is checked.
    let current: Option<(Option<String>, Option<String>)> = conn
        .query_row(
            "SELECT goal_id, milestone_id FROM tasks WHERE id = ?1",
            [task_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let (current_goal, milestone_id) = current.unwrap_or((None, None));
    if milestone_id.is_some() {
        if current_goal != edit.goal_id {
            return Err(invalid("A milestone's task always belongs to its goal"));
        }
        if edit.repeat.is_some() {
            return Err(invalid("A milestone can't repeat"));
        }
    } else if current_goal != edit.goal_id {
        check_goal(conn, &edit.goal_id)?;
    }
    let changed = conn.execute(
        "UPDATE tasks SET title = ?2, notes = ?3, due = ?4, priority = ?5, tag = ?6, repeat = ?7, goal_id = ?8
         WHERE id = ?1 AND deleted_date IS NULL",
        params![
            task_id,
            title,
            edit.notes.trim(),
            edit.due,
            edit.priority.as_sql(),
            tag,
            edit.repeat.map(Repeat::as_sql),
            edit.goal_id
        ],
    )?;
    if changed == 0 {
        return Err(invalid("Task not found"));
    }
    if let Some(milestone_id) = milestone_id {
        // Renaming the task renames the milestone, so the two never disagree.
        conn.execute(
            "UPDATE milestones SET title = ?2 WHERE id = ?1",
            params![milestone_id, title],
        )?;
    }
    Ok(())
}

pub fn get_task(conn: &Connection, task_id: &str, today: &str) -> Result<Task, CoreError> {
    parse_date(today)?;
    conn.query_row(
        &format!("SELECT {TASK_COLUMNS} FROM tasks t WHERE t.id = ?1"),
        [task_id],
        |row| task_from_row(row, today),
    )
    .optional()?
    .ok_or_else(|| invalid("Task not found"))
}

/// The next due date for a repeating task. It starts from the task's own date and
/// skips dates in the past, so a task that is weeks late doesn't spawn late copies.
pub fn next_due(
    due: Option<&str>,
    repeat: Repeat,
    today: NaiveDate,
) -> Result<NaiveDate, CoreError> {
    let anchor = match due {
        Some(d) => parse_date(d)?,
        None => today,
    };
    let step = |from: NaiveDate, count: u32| -> NaiveDate {
        match repeat {
            Repeat::Daily => from + Days::new(1),
            Repeat::Weekdays => {
                let mut d = from + Days::new(1);
                while matches!(d.weekday(), Weekday::Sat | Weekday::Sun) {
                    d = d + Days::new(1);
                }
                d
            }
            Repeat::Weekly => from + Days::new(7),
            // Counted from the original day, so Jan 31 goes to Feb 28, then Mar 31.
            Repeat::Monthly => anchor
                .checked_add_months(Months::new(count))
                .unwrap_or(from + Days::new(30)),
        }
    };
    let mut date = step(anchor, 1);
    let mut count = 1;
    while date < today && count < 400 {
        count += 1;
        date = step(date, count);
    }
    Ok(date)
}

pub fn toggle_task(
    conn: &mut Connection,
    task_id: &str,
    today: &str,
) -> Result<TaskToggleResult, CoreError> {
    let today_date = parse_date(today)?;
    // A task that mirrors a milestone is ticked through the milestone, which owns the reward.
    let milestone: Option<String> = conn
        .query_row(
            "SELECT milestone_id FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
            [task_id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    if let Some(milestone_id) = milestone {
        let reward = crate::goals::toggle_milestone(conn, &milestone_id, today)?;
        let done: bool = conn.query_row(
            "SELECT done_date IS NOT NULL FROM tasks WHERE id = ?1",
            [task_id],
            |r| r.get(0),
        )?;
        return Ok(TaskToggleResult {
            done,
            next_due: None,
            reward,
        });
    }
    let tx = conn.transaction()?;
    let (done_date, due, repeat, next_id): (Option<String>, Option<String>, Option<Repeat>, Option<String>) = tx
        .query_row(
            "SELECT done_date, due, repeat, next_id FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
            [task_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .optional()?
        .ok_or_else(|| invalid("Task not found"))?;

    let mut result = TaskToggleResult {
        done: false,
        next_due: None,
        reward: LogResult::default(),
    };
    if done_date.is_none() {
        tx.execute(
            "UPDATE tasks SET done_date = ?2 WHERE id = ?1",
            params![task_id, today],
        )?;
        result.done = true;
        if let (Some(repeat), None) = (repeat, &next_id) {
            let due_next = next_due(due.as_deref(), repeat, today_date)?.to_string();
            let new = new_id();
            tx.execute(
                "INSERT INTO tasks (id, title, notes, due, priority, tag, repeat, created_date, prev_id, goal_id)
                 SELECT ?2, title, notes, ?3, priority, tag, repeat, ?4, id, goal_id FROM tasks WHERE id = ?1",
                params![task_id, new, due_next, today],
            )?;
            tx.execute(
                "INSERT INTO task_items (id, task_id, text, position, done)
                 SELECT lower(hex(randomblob(16))), ?2, text, position, 0
                 FROM task_items WHERE task_id = ?1",
                params![task_id, new],
            )?;
            tx.execute(
                "UPDATE tasks SET next_id = ?2 WHERE id = ?1",
                params![task_id, new],
            )?;
            result.next_due = Some(due_next);
        }
    } else {
        tx.execute("UPDATE tasks SET done_date = NULL WHERE id = ?1", [task_id])?;
        // Taking the tick back removes the copy it created, unless that copy was finished.
        if let Some(next) = next_id {
            tx.execute(
                "DELETE FROM tasks WHERE id = ?1 AND done_date IS NULL",
                [&next],
            )?;
            tx.execute(
                "UPDATE tasks SET next_id = NULL
                 WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM tasks WHERE id = ?2)",
                params![task_id, next],
            )?;
        }
    }
    tx.commit()?;
    Ok(result)
}

/// Moves a task to the trash. `restore_task` brings it back.
pub fn delete_task(conn: &Connection, task_id: &str, today: &str) -> Result<(), CoreError> {
    parse_date(today)?;
    let mirrored: bool = conn
        .query_row(
            "SELECT milestone_id IS NOT NULL FROM tasks WHERE id = ?1",
            [task_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(false);
    if mirrored {
        return Err(invalid(
            "This task is a milestone. Remove it from its goal's page instead.",
        ));
    }
    let changed = conn.execute(
        "UPDATE tasks SET deleted_date = ?2 WHERE id = ?1 AND deleted_date IS NULL",
        params![task_id, today],
    )?;
    if changed == 0 {
        return Err(invalid("Task not found"));
    }
    Ok(())
}

pub fn restore_task(conn: &Connection, task_id: &str) -> Result<(), CoreError> {
    let changed = conn.execute(
        "UPDATE tasks SET deleted_date = NULL WHERE id = ?1 AND deleted_date IS NOT NULL",
        [task_id],
    )?;
    if changed == 0 {
        return Err(invalid("Task not found in the trash"));
    }
    Ok(())
}

/// Removes tasks that have been in the trash for `TRASH_DAYS` or more. Returns how many.
pub fn purge_trash(conn: &Connection, today: &str) -> Result<u32, CoreError> {
    let cutoff = parse_date(today)? - Days::new(TRASH_DAYS);
    let removed = conn.execute(
        "DELETE FROM tasks WHERE deleted_date IS NOT NULL AND deleted_date <= ?1",
        [cutoff.to_string()],
    )?;
    Ok(removed as u32)
}

pub fn list_trash(conn: &Connection, today: &str) -> Result<Vec<Task>, CoreError> {
    parse_date(today)?;
    let mut stmt = conn.prepare(&format!(
        "SELECT {TASK_COLUMNS} FROM tasks t WHERE t.deleted_date IS NOT NULL AND {VISIBLE}
         ORDER BY t.deleted_date DESC, t.rowid DESC"
    ))?;
    let rows = stmt.query_map([], |row| task_from_row(row, today))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

fn priority_rank(p: Priority) -> u8 {
    match p {
        Priority::High => 0,
        Priority::Med => 1,
        Priority::Low => 2,
    }
}

pub fn list_tasks(
    conn: &Connection,
    filter: TaskFilter,
    sort: TaskSort,
    today: &str,
) -> Result<Vec<Task>, CoreError> {
    parse_date(today)?;
    // rowid keeps creation order, which "Newest" and ties rely on.
    let mut stmt = conn.prepare(&format!(
        "SELECT {TASK_COLUMNS}, t.rowid FROM tasks t
         WHERE t.deleted_date IS NULL AND {VISIBLE} ORDER BY t.rowid"
    ))?;
    let mut tasks: Vec<(i64, Task)> = stmt
        .query_map([], |row| Ok((row.get(14)?, task_from_row(row, today)?)))?
        .collect::<Result<_, _>>()?;

    tasks.retain(|(_, t)| match filter {
        TaskFilter::All => true,
        TaskFilter::Today => !t.done && t.due.as_deref() == Some(today),
        TaskFilter::Overdue => t.overdue,
        TaskFilter::Done => t.done,
    });
    // A missing due date sorts after every real date.
    let due_key = |t: &Task| t.due.clone().unwrap_or_else(|| "9999-99-99".into());
    tasks.sort_by(|(ra, a), (rb, b)| {
        a.done.cmp(&b.done).then_with(|| match sort {
            TaskSort::Due => due_key(a).cmp(&due_key(b)).then(ra.cmp(rb)),
            TaskSort::Priority => priority_rank(a.priority)
                .cmp(&priority_rank(b.priority))
                .then_with(|| due_key(a).cmp(&due_key(b)))
                .then(ra.cmp(rb)),
            TaskSort::Newest => rb.cmp(ra),
            TaskSort::Alphabetical => a
                .title
                .to_lowercase()
                .cmp(&b.title.to_lowercase())
                .then(ra.cmp(rb)),
        })
    });
    Ok(tasks.into_iter().map(|(_, t)| t).collect())
}

/// Every task linked to one goal: open ones first, then done ones.
pub fn list_goal_tasks(
    conn: &Connection,
    goal_id: &str,
    today: &str,
) -> Result<Vec<Task>, CoreError> {
    let mut tasks = list_tasks(conn, TaskFilter::All, TaskSort::Due, today)?;
    tasks.retain(|t| t.goal_id.as_deref() == Some(goal_id));
    Ok(tasks)
}

/// Open tasks due today or earlier, for the Today screen: overdue first, then by priority.
pub fn due_tasks(conn: &Connection, today: &str) -> Result<Vec<Task>, CoreError> {
    let mut tasks = list_tasks(conn, TaskFilter::All, TaskSort::Due, today)?;
    tasks.retain(|t| !t.done && t.due.as_deref().is_some_and(|d| d <= today));
    tasks.sort_by_key(|t| (t.due.clone(), priority_rank(t.priority)));
    Ok(tasks)
}

// Checklist

pub fn list_items(conn: &Connection, task_id: &str) -> Result<Vec<TaskItem>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, task_id, text, done FROM task_items WHERE task_id = ?1 ORDER BY position",
    )?;
    let rows = stmt.query_map([task_id], |r| {
        Ok(TaskItem {
            id: r.get(0)?,
            task_id: r.get(1)?,
            text: r.get(2)?,
            done: r.get::<_, i64>(3)? == 1,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

pub fn add_item(conn: &Connection, task_id: &str, text: &str) -> Result<String, CoreError> {
    let text = clean_title(text)?;
    let exists: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ?1 AND deleted_date IS NULL)",
        [task_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(invalid("Task not found"));
    }
    let id = new_id();
    conn.execute(
        "INSERT INTO task_items (id, task_id, text, position)
         VALUES (?1, ?2, ?3, (SELECT COALESCE(MAX(position) + 1, 0) FROM task_items WHERE task_id = ?2))",
        params![id, task_id, text],
    )?;
    Ok(id)
}

pub fn toggle_item(conn: &Connection, item_id: &str) -> Result<(), CoreError> {
    let changed = conn.execute(
        "UPDATE task_items SET done = 1 - done WHERE id = ?1",
        [item_id],
    )?;
    if changed == 0 {
        return Err(invalid("Checklist item not found"));
    }
    Ok(())
}

pub fn delete_item(conn: &Connection, item_id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM task_items WHERE id = ?1", [item_id])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        parse_date(s).unwrap()
    }

    #[test]
    fn daily_skips_past_dates_so_late_tasks_do_not_spawn_late_copies() {
        let next = next_due(Some("2026-10-01"), Repeat::Daily, d("2026-11-05")).unwrap();
        assert_eq!(next, d("2026-11-05"));
    }

    #[test]
    fn weekdays_jump_over_the_weekend() {
        // 2026-11-06 is a Friday.
        let next = next_due(Some("2026-11-06"), Repeat::Weekdays, d("2026-11-06")).unwrap();
        assert_eq!(next, d("2026-11-09"));
    }

    #[test]
    fn monthly_keeps_the_original_day_after_a_short_month() {
        let feb = next_due(Some("2026-01-31"), Repeat::Monthly, d("2026-01-31")).unwrap();
        assert_eq!(feb, d("2026-02-28"));
        let mar = next_due(Some("2026-01-31"), Repeat::Monthly, d("2026-03-01")).unwrap();
        assert_eq!(mar, d("2026-03-31"));
    }
}
