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
    t.milestone_id,
    t.deleted_date, t.archived_date, t.pushed_count, t.repeat_every, t.repeat_days, t.icon, t.sample";

/// What `toggle_task` reads first: done date, due, repeat, next copy, repeat every, repeat days.
type ToggleRow = (
    Option<String>,
    Option<String>,
    Option<Repeat>,
    Option<String>,
    u32,
    Option<u8>,
);

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
        deleted_date: row.get(14)?,
        archived_date: row.get(15)?,
        pushed_count: row.get(16)?,
        repeat_every: row.get(17)?,
        repeat_days: row.get(18)?,
        icon: row.get(19)?,
        sample: row.get(20)?,
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
    let current: Option<(Option<String>, Option<String>, Option<String>)> = conn
        .query_row(
            "SELECT goal_id, milestone_id, due FROM tasks WHERE id = ?1",
            [task_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    let (current_goal, milestone_id, old_due) = current.unwrap_or((None, None, None));
    let pushed = was_pushed(old_due.as_deref(), edit.due.as_deref());
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
        "UPDATE tasks SET title = ?2, notes = ?3, due = ?4, priority = ?5, tag = ?6, repeat = ?7, goal_id = ?8,
             pushed_count = pushed_count + ?9,
             -- Dropping the repeat, or changing its unit, clears the custom rule.
             repeat_every = CASE WHEN ?7 IS NULL OR ?7 <> repeat THEN 1 ELSE repeat_every END,
             repeat_days = CASE WHEN ?7 = 'weekly' THEN repeat_days ELSE NULL END
         WHERE id = ?1 AND deleted_date IS NULL",
        params![
            task_id,
            title,
            edit.notes.trim(),
            edit.due,
            edit.priority.as_sql(),
            tag,
            edit.repeat.map(Repeat::as_sql),
            edit.goal_id,
            pushed
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

/// 1 when the due date moved to a later day (a "push back"), otherwise 0.
fn was_pushed(old: Option<&str>, new: Option<&str>) -> i64 {
    // Dates are YYYY-MM-DD, so comparing the text compares the days.
    i64::from(matches!((old, new), (Some(o), Some(n)) if n > o))
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

/// The next due date for a plain repeat (every 1 day, week or month). Used by the tests.
#[cfg(test)]
pub fn next_due(
    due: Option<&str>,
    repeat: Repeat,
    today: NaiveDate,
) -> Result<NaiveDate, CoreError> {
    next_due_custom(due, repeat, 1, None, today)
}

/// The next due date for a repeating task. It starts from the task's own date and
/// skips dates in the past, so a task that is weeks late doesn't spawn late copies.
/// Custom rules: repeat every `every` days, weeks or months, and for weekly repeats
/// optionally only on the weekdays in `days` (Monday = 1).
pub fn next_due_custom(
    due: Option<&str>,
    repeat: Repeat,
    every: u32,
    days: Option<u8>,
    today: NaiveDate,
) -> Result<NaiveDate, CoreError> {
    let anchor = match due {
        Some(d) => parse_date(d)?,
        None => today,
    };
    let every = every.clamp(1, 365);
    // Weekly with chosen weekdays: the first allowed day after the anchor that is not
    // in the past. Weeks are counted from the anchor's week, so "every 2 weeks on
    // Mon and Thu" keeps its rhythm.
    if let (Repeat::Weekly, Some(mask)) = (repeat, days.filter(|m| m & 127 != 0)) {
        let week_of = |d: NaiveDate| d - Days::new(u64::from(d.weekday().num_days_from_monday()));
        let anchor_week = week_of(anchor);
        let mut date = (anchor + Days::new(1)).max(today);
        // A matching day always shows up within `every` weeks, so this loop ends.
        for _ in 0..(7 * every + 14) {
            let weeks = (week_of(date) - anchor_week).num_days() / 7;
            let allowed = mask & (1 << date.weekday().num_days_from_monday()) != 0;
            if allowed && weeks % i64::from(every) == 0 {
                return Ok(date);
            }
            date = date + Days::new(1);
        }
        return Ok(date);
    }
    let step = |from: NaiveDate, count: u32| -> NaiveDate {
        match repeat {
            Repeat::Daily => from + Days::new(u64::from(every)),
            Repeat::Weekdays => {
                let mut d = from + Days::new(1);
                while matches!(d.weekday(), Weekday::Sat | Weekday::Sun) {
                    d = d + Days::new(1);
                }
                d
            }
            Repeat::Weekly => from + Days::new(7 * u64::from(every)),
            // Counted from the original day, so Jan 31 goes to Feb 28, then Mar 31.
            Repeat::Monthly => anchor
                .checked_add_months(Months::new(count * every))
                .unwrap_or(from + Days::new(30 * u64::from(every))),
        }
    };
    let mut date = step(anchor, 1);
    let mut count = 1;
    while date < today && count < 1000 {
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
    let (done_date, due, repeat, next_id, every, days): ToggleRow = tx
        .query_row(
            "SELECT done_date, due, repeat, next_id, repeat_every, repeat_days
             FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
            [task_id],
            |r| {
                Ok((
                    r.get(0)?,
                    r.get(1)?,
                    r.get(2)?,
                    r.get(3)?,
                    r.get(4)?,
                    r.get(5)?,
                ))
            },
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
            let due_next =
                next_due_custom(due.as_deref(), repeat, every, days, today_date)?.to_string();
            let new = new_id();
            tx.execute(
                "INSERT INTO tasks (id, title, notes, due, priority, tag, repeat, created_date, prev_id, goal_id,
                                    repeat_every, repeat_days)
                 SELECT ?2, title, notes, ?3, priority, tag, repeat, ?4, id, goal_id, repeat_every, repeat_days
                 FROM tasks WHERE id = ?1",
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
         WHERE t.deleted_date IS NULL AND t.archived_date IS NULL AND {VISIBLE} ORDER BY t.rowid"
    ))?;
    let mut tasks: Vec<(i64, Task)> = stmt
        .query_map([], |row| Ok((row.get(21)?, task_from_row(row, today)?)))?
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

// Archive, bulk edit, tags, custom repeat

/// A milestone's task can't be archived, trashed, moved or repeated by itself.
fn is_milestone_task(conn: &Connection, task_id: &str) -> Result<bool, CoreError> {
    Ok(conn
        .query_row(
            "SELECT milestone_id IS NOT NULL FROM tasks WHERE id = ?1",
            [task_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(false))
}

/// Hides a task in the archive. `unarchive_task` brings it back.
pub fn archive_task(conn: &Connection, task_id: &str, today: &str) -> Result<(), CoreError> {
    parse_date(today)?;
    if is_milestone_task(conn, task_id)? {
        return Err(invalid(
            "This task is a milestone. It stays with its goal's page.",
        ));
    }
    let changed = conn.execute(
        "UPDATE tasks SET archived_date = ?2
         WHERE id = ?1 AND archived_date IS NULL AND deleted_date IS NULL",
        params![task_id, today],
    )?;
    if changed == 0 {
        return Err(invalid("Task not found"));
    }
    Ok(())
}

pub fn unarchive_task(conn: &Connection, task_id: &str) -> Result<(), CoreError> {
    let changed = conn.execute(
        "UPDATE tasks SET archived_date = NULL
         WHERE id = ?1 AND archived_date IS NOT NULL AND deleted_date IS NULL",
        [task_id],
    )?;
    if changed == 0 {
        return Err(invalid("Task not found in the archive"));
    }
    Ok(())
}

/// Archived tasks, newest archived first. Tasks in the trash are not listed.
pub fn list_archived(conn: &Connection, today: &str) -> Result<Vec<Task>, CoreError> {
    parse_date(today)?;
    let mut stmt = conn.prepare(&format!(
        "SELECT {TASK_COLUMNS} FROM tasks t
         WHERE t.archived_date IS NOT NULL AND t.deleted_date IS NULL
         ORDER BY t.archived_date DESC, t.rowid DESC"
    ))?;
    let rows = stmt.query_map([], |row| task_from_row(row, today))?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Moves every finished task in the archive to the trash. Returns how many.
pub fn trash_archived_done(conn: &Connection, today: &str) -> Result<u32, CoreError> {
    parse_date(today)?;
    let changed = conn.execute(
        "UPDATE tasks SET deleted_date = ?1
         WHERE archived_date IS NOT NULL AND done_date IS NOT NULL AND deleted_date IS NULL",
        [today],
    )?;
    Ok(changed as u32)
}

/// Archives finished tasks that were done `after_days` or more days ago (0 means any
/// finished task). Milestone tasks are never archived. Returns how many.
pub fn auto_archive(conn: &Connection, after_days: u32, today: &str) -> Result<u32, CoreError> {
    let cutoff = parse_date(today)? - Days::new(u64::from(after_days));
    let changed = conn.execute(
        "UPDATE tasks SET archived_date = ?2
         WHERE done_date IS NOT NULL AND done_date <= ?1
           AND archived_date IS NULL AND deleted_date IS NULL AND milestone_id IS NULL",
        params![cutoff.to_string(), today],
    )?;
    Ok(changed as u32)
}

/// Removes one task from the trash for good.
pub fn delete_task_forever(conn: &Connection, task_id: &str) -> Result<(), CoreError> {
    let removed = conn.execute(
        "DELETE FROM tasks WHERE id = ?1 AND deleted_date IS NOT NULL",
        [task_id],
    )?;
    if removed == 0 {
        return Err(invalid("Task not found in the trash"));
    }
    Ok(())
}

/// Removes everything in the trash for good. Returns how many tasks.
pub fn empty_trash(conn: &Connection) -> Result<u32, CoreError> {
    let removed = conn.execute("DELETE FROM tasks WHERE deleted_date IS NOT NULL", [])?;
    Ok(removed as u32)
}

/// Applies one change to many tasks. Everything except "done" runs in a single
/// transaction, so it happens for all of them or none. "Done" finishes each task
/// through `toggle_task`, which has its own transaction because repeating tasks and
/// milestones need extra steps. Milestone tasks are skipped by every change except done.
pub fn bulk_update(
    conn: &mut Connection,
    ids: &[String],
    edit: BulkEdit,
    today: &str,
) -> Result<BulkResult, CoreError> {
    parse_date(today)?;
    validate_due(&edit.due)?;
    let tag = clean_tag(edit.tag.clone())?;
    if edit.change_goal {
        check_goal(conn, &edit.goal_id)?;
    }
    let mut result = BulkResult::default();
    if edit.done {
        for id in ids {
            let open: Option<bool> = conn
                .query_row(
                    "SELECT done_date IS NULL FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
                    [id],
                    |r| r.get(0),
                )
                .optional()?;
            if open != Some(true) {
                continue;
            }
            let toggled = toggle_task(conn, id, today)?;
            result.changed += 1;
            result.reward.drew += toggled.reward.drew;
            result.reward.no_treat += toggled.reward.no_treat;
            result.reward.treats_removed += toggled.reward.treats_removed;
            result.reward.jar_empty |= toggled.reward.jar_empty;
        }
        return Ok(result);
    }
    let tx = conn.transaction()?;
    for id in ids {
        let row: Option<(bool, Option<String>)> = tx
            .query_row(
                "SELECT milestone_id IS NOT NULL, due FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((mirrored, old_due)) = row else {
            continue;
        };
        if mirrored {
            result.skipped_milestones += 1;
            continue;
        }
        if edit.change_due {
            let pushed = was_pushed(old_due.as_deref(), edit.due.as_deref());
            tx.execute(
                "UPDATE tasks SET due = ?2, pushed_count = pushed_count + ?3 WHERE id = ?1",
                params![id, edit.due, pushed],
            )?;
        }
        if let Some(priority) = edit.priority {
            tx.execute(
                "UPDATE tasks SET priority = ?2 WHERE id = ?1",
                params![id, priority.as_sql()],
            )?;
        }
        if edit.change_tag {
            tx.execute("UPDATE tasks SET tag = ?2 WHERE id = ?1", params![id, tag])?;
        }
        if edit.change_goal {
            tx.execute(
                "UPDATE tasks SET goal_id = ?2 WHERE id = ?1",
                params![id, edit.goal_id],
            )?;
        }
        if edit.archive {
            tx.execute(
                "UPDATE tasks SET archived_date = COALESCE(archived_date, ?2) WHERE id = ?1",
                params![id, today],
            )?;
        }
        if edit.trash {
            tx.execute(
                "UPDATE tasks SET deleted_date = ?2 WHERE id = ?1",
                params![id, today],
            )?;
        }
        result.changed += 1;
    }
    tx.commit()?;
    Ok(result)
}

/// Every tag in use on tasks that are not trashed or archived, with counts.
/// "Uni" and "uni" count as the same tag.
pub fn list_tags(conn: &Connection) -> Result<Vec<TagCount>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT MIN(tag), SUM(done_date IS NULL), SUM(done_date IS NOT NULL)
         FROM tasks
         WHERE tag IS NOT NULL AND deleted_date IS NULL AND archived_date IS NULL
         GROUP BY lower(tag) ORDER BY lower(tag)",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(TagCount {
            tag: r.get(0)?,
            open: r.get(1)?,
            done: r.get(2)?,
        })
    })?;
    Ok(rows.collect::<Result<_, _>>()?)
}

/// Sets the custom part of a repeat: "every `every` days/weeks/months", and for weekly
/// repeats the weekdays (Monday = 1 up to Sunday = 64). The task must already repeat.
pub fn set_repeat_rule(
    conn: &Connection,
    task_id: &str,
    every: u32,
    days: Option<u8>,
) -> Result<(), CoreError> {
    if !(1..=365).contains(&every) {
        return Err(invalid("Repeat every 1 to 365"));
    }
    let repeat: Option<String> = conn
        .query_row(
            "SELECT repeat FROM tasks WHERE id = ?1 AND deleted_date IS NULL",
            [task_id],
            |r| r.get(0),
        )
        .optional()?
        .ok_or_else(|| invalid("Task not found"))?;
    let Some(repeat) = repeat else {
        return Err(invalid("Choose a repeat first"));
    };
    if is_milestone_task(conn, task_id)? {
        return Err(invalid("A milestone can't repeat"));
    }
    // All seven days is the same as no choice, so it is stored as NULL like habits do.
    let days = days.filter(|m| *m != 127);
    if let Some(mask) = days {
        if repeat != "weekly" {
            return Err(invalid("Weekdays only work with a weekly repeat"));
        }
        if mask == 0 || mask > 127 {
            return Err(invalid("Choose at least one weekday"));
        }
    }
    if repeat == "weekdays" && every != 1 {
        return Err(invalid("A weekday repeat can't be every N"));
    }
    conn.execute(
        "UPDATE tasks SET repeat_every = ?2, repeat_days = ?3 WHERE id = ?1",
        params![task_id, every, days],
    )?;
    Ok(())
}

/// Replaces a task's checklist with `lines` (one step per line, blank lines ignored).
/// A step whose text is unchanged keeps its tick.
pub fn replace_items(
    conn: &mut Connection,
    task_id: &str,
    lines: &[String],
) -> Result<(), CoreError> {
    let steps = lines
        .iter()
        .filter(|l| !l.trim().is_empty())
        .map(|l| clean_title(l))
        .collect::<Result<Vec<_>, _>>()?;
    let tx = conn.transaction()?;
    let exists: bool = tx.query_row(
        "SELECT EXISTS (SELECT 1 FROM tasks WHERE id = ?1 AND deleted_date IS NULL)",
        [task_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(invalid("Task not found"));
    }
    let mut ticked: Vec<String> = {
        let mut stmt = tx.prepare("SELECT text FROM task_items WHERE task_id = ?1 AND done = 1")?;
        let rows = stmt.query_map([task_id], |r| r.get(0))?;
        rows.collect::<Result<_, _>>()?
    };
    tx.execute("DELETE FROM task_items WHERE task_id = ?1", [task_id])?;
    for (position, text) in steps.iter().enumerate() {
        // Each old tick is used once, so two equal steps don't both inherit it.
        let done = match ticked.iter().position(|t| t == text) {
            Some(i) => {
                ticked.remove(i);
                1
            }
            None => 0,
        };
        tx.execute(
            "INSERT INTO task_items (id, task_id, text, position, done) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![new_id(), task_id, text, position as i64, done],
        )?;
    }
    tx.commit()?;
    Ok(())
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
    fn every_n_days_weeks_and_months() {
        let today = d("2026-11-05");
        let go = |due: &str, r, n, days| next_due_custom(Some(due), r, n, days, today).unwrap();
        assert_eq!(go("2026-11-05", Repeat::Daily, 3, None), d("2026-11-08"));
        assert_eq!(go("2026-11-05", Repeat::Weekly, 2, None), d("2026-11-19"));
        assert_eq!(go("2026-09-05", Repeat::Monthly, 2, None), d("2026-11-05"));
    }

    #[test]
    fn weekly_on_chosen_weekdays_keeps_the_rhythm() {
        // 2026-11-05 is a Thursday. Monday = 1, Thursday = 8.
        let mon_thu = Some(1 | 8);
        let today = d("2026-11-05");
        let next =
            |every| next_due_custom(Some("2026-11-05"), Repeat::Weekly, every, mon_thu, today);
        // Weekly: the next allowed day is Monday the 9th.
        assert_eq!(next(1).unwrap(), d("2026-11-09"));
        // Every 2 weeks: the week of the 9th is skipped, so it is Monday the 16th.
        assert_eq!(next(2).unwrap(), d("2026-11-16"));
    }

    #[test]
    fn monthly_keeps_the_original_day_after_a_short_month() {
        let feb = next_due(Some("2026-01-31"), Repeat::Monthly, d("2026-01-31")).unwrap();
        assert_eq!(feb, d("2026-02-28"));
        let mar = next_due(Some("2026-01-31"), Repeat::Monthly, d("2026-03-01")).unwrap();
        assert_eq!(mar, d("2026-03-31"));
    }
}
