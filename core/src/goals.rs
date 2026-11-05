//! Goals, entries, milestones, and keeping completed targets in step with them.

use std::collections::BTreeMap;

use chrono::NaiveDate;
use rusqlite::{Connection, OptionalExtension, Row, params};

use crate::CoreError;
use crate::dates::{
    is_scheduled, next_start, parse_date, period_start_ws, previous_start, week_start,
};
use crate::model::*;
use crate::rewards;
use crate::stats;

/// Tolerance for summed decimal amounts, such as 0.1 + 0.2 against a target of 0.3.
pub const EPSILON: f64 = 1e-9;

pub fn invalid(reason: impl Into<String>) -> CoreError {
    CoreError::Invalid {
        reason: reason.into(),
    }
}

pub(crate) fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

pub(crate) fn clean_optional(value: Option<String>) -> Option<String> {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

pub(crate) fn clean_title(title: &str) -> Result<String, CoreError> {
    let title = title.trim();
    if title.is_empty() {
        return Err(invalid("A title is required"));
    }
    Ok(title.to_string())
}

fn validate_deadline(deadline: &Option<String>) -> Result<(), CoreError> {
    if let Some(deadline) = deadline {
        parse_date(deadline)?;
    }
    Ok(())
}

fn validate_targets(goal: &NewGoal) -> Result<(), CoreError> {
    let positive = |value: Option<f64>| value.is_none_or(|v| v.is_finite() && v > 0.0);
    if !positive(goal.period_target) || !positive(goal.total_target) {
        return Err(invalid("Targets must be greater than zero"));
    }
    if goal.period.is_some() != goal.period_target.is_some() {
        return Err(invalid("A period and its target must be set together"));
    }
    if let Some(mask) = goal.weekdays {
        if goal.kind != GoalKind::Habit || goal.period != Some(Period::Day) {
            return Err(invalid("Weekdays only apply to daily habits"));
        }
        if !(1..=127).contains(&mask) {
            return Err(invalid("Choose at least one weekday"));
        }
    }
    match goal.kind {
        GoalKind::YesNo => {
            if goal.period.is_some() || goal.total_target.is_some() {
                return Err(invalid("Yes/no goals can't have targets"));
            }
        }
        GoalKind::Habit => {
            if goal.total_target.is_some() {
                return Err(invalid("Habits can't have a total target"));
            }
            let (Some(period), Some(target)) = (goal.period, goal.period_target) else {
                return Err(invalid("Habits need a target for each period"));
            };
            // Habits allow one check-in per day, so the target must be reachable
            // in every period. February sets the limit for months.
            let max = match period {
                Period::Day => 1.0,
                Period::Week => 7.0,
                Period::Month => 28.0,
            };
            if target.fract() != 0.0 || target > max {
                return Err(invalid(format!(
                    "Habit target must be a whole number up to {max}"
                )));
            }
        }
        GoalKind::Numeric => {}
    }
    Ok(())
}

pub fn create_goal(conn: &mut Connection, goal: NewGoal, today: &str) -> Result<String, CoreError> {
    parse_date(today)?;
    let title = clean_title(&goal.title)?;
    validate_targets(&goal)?;
    validate_deadline(&goal.deadline)?;
    let milestones = goal
        .milestones
        .iter()
        .map(|m| clean_title(m))
        .collect::<Result<Vec<_>, _>>()?;

    let id = new_id();
    let tx = conn.transaction()?;
    tx.execute(
        "INSERT INTO goals (id, title, notes, kind, category, period, period_target, total_target,
                            unit, deadline, created_date, weekdays)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
        params![
            id,
            title,
            goal.notes.trim(),
            goal.kind.as_sql(),
            clean_optional(goal.category),
            goal.period.map(Period::as_sql),
            goal.period_target,
            goal.total_target,
            clean_optional(goal.unit),
            goal.deadline,
            today,
            // Every day is the same as no schedule.
            goal.weekdays.filter(|mask| *mask != 127),
        ],
    )?;
    for (position, title) in milestones.iter().enumerate() {
        let milestone_id = new_id();
        tx.execute(
            "INSERT INTO milestones (id, goal_id, title, position) VALUES (?1, ?2, ?3, ?4)",
            params![milestone_id, id, title, position as i64],
        )?;
        crate::tasks::create_milestone_task(&tx, &id, &milestone_id, title, today)?;
    }
    tx.commit()?;
    Ok(id)
}

/// Creates a quit habit. It is always a daily habit with a target of one clean day,
/// so the only things to choose are a name, notes and a category. See "Quit habits"
/// in PLAN.md for the rules. Quit habits never earn rewards.
pub fn create_avoid_goal(
    conn: &mut Connection,
    title: &str,
    notes: &str,
    category: Option<String>,
    today: &str,
) -> Result<String, CoreError> {
    parse_date(today)?;
    let title = clean_title(title)?;
    let id = new_id();
    conn.execute(
        "INSERT INTO goals (id, title, notes, kind, category, period, period_target,
                            created_date, avoid)
         VALUES (?1, ?2, ?3, 'habit', ?4, 'day', 1, ?5, 1)",
        params![id, title, notes.trim(), clean_optional(category), today],
    )?;
    Ok(id)
}

pub fn update_goal(conn: &Connection, goal_id: &str, edit: GoalEdit) -> Result<(), CoreError> {
    let title = clean_title(&edit.title)?;
    validate_deadline(&edit.deadline)?;
    let changed = conn.execute(
        "UPDATE goals SET title = ?2, notes = ?3, category = ?4, unit = ?5, deadline = ?6 WHERE id = ?1",
        params![
            goal_id,
            title,
            edit.notes.trim(),
            clean_optional(edit.category),
            clean_optional(edit.unit),
            edit.deadline,
        ],
    )?;
    if changed == 0 {
        return Err(invalid("That goal no longer exists"));
    }
    Ok(())
}

pub fn set_archived(conn: &Connection, goal_id: &str, archived: bool) -> Result<(), CoreError> {
    conn.execute(
        "UPDATE goals SET archived = ?2 WHERE id = ?1",
        params![goal_id, archived],
    )?;
    Ok(())
}

pub fn delete_goal(conn: &Connection, goal_id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM goals WHERE id = ?1", params![goal_id])?;
    Ok(())
}

const GOAL_COLUMNS: &str = "id, title, notes, kind, category, period, period_target, total_target,
    unit, deadline, created_date, archived,
    (SELECT COALESCE(SUM(amount), 0) FROM entries e WHERE e.goal_id = goals.id),
    weekdays,
    (SELECT COUNT(*) FROM milestones m WHERE m.goal_id = goals.id AND m.done_date IS NOT NULL),
    (SELECT COUNT(*) FROM milestones m WHERE m.goal_id = goals.id),
    avoid, icon, sample";

fn goal_from_row(row: &Row<'_>) -> rusqlite::Result<Goal> {
    let kind: GoalKind = row.get(3)?;
    let total_target: Option<f64> = row.get(7)?;
    let total_logged: f64 = row.get(12)?;
    let done = match kind {
        GoalKind::YesNo => total_logged > 0.0,
        GoalKind::Numeric => total_target.is_some_and(|t| total_logged + EPSILON >= t),
        GoalKind::Habit => false,
    };
    Ok(Goal {
        id: row.get(0)?,
        title: row.get(1)?,
        notes: row.get(2)?,
        kind,
        category: row.get(4)?,
        period: row.get(5)?,
        period_target: row.get(6)?,
        total_target,
        unit: row.get(8)?,
        deadline: row.get(9)?,
        created_date: row.get(10)?,
        archived: row.get(11)?,
        total_logged,
        done,
        weekdays: row.get(13)?,
        milestones_done: row.get(14)?,
        milestones_total: row.get(15)?,
        avoid: row.get(16)?,
        icon: row.get(17)?,
        sample: row.get(18)?,
    })
}

pub fn get_goal(conn: &Connection, goal_id: &str) -> Result<Goal, CoreError> {
    conn.query_row(
        &format!("SELECT {GOAL_COLUMNS} FROM goals WHERE id = ?1"),
        params![goal_id],
        goal_from_row,
    )
    .optional()?
    .ok_or_else(|| invalid("That goal no longer exists"))
}

pub fn list_goals(conn: &Connection, archived: bool) -> Result<Vec<Goal>, CoreError> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {GOAL_COLUMNS} FROM goals WHERE archived = ?1
         ORDER BY category IS NULL, category COLLATE NOCASE, position, created_date, rowid"
    ))?;
    let goals = stmt
        .query_map(params![archived], goal_from_row)?
        .collect::<Result<_, _>>()?;
    Ok(goals)
}

/// Moves a goal one place up or down among the goals in its category.
pub fn move_goal(conn: &mut Connection, goal_id: &str, earlier: bool) -> Result<(), CoreError> {
    let tx = conn.transaction()?;
    let goal = get_goal(&tx, goal_id)?;
    let neighbours: Vec<String> = list_goals(&tx, goal.archived)?
        .into_iter()
        .filter(|g| g.category == goal.category)
        .map(|g| g.id)
        .collect();
    let Some(index) = neighbours.iter().position(|id| id == goal_id) else {
        return Ok(());
    };
    let other = if earlier {
        index.checked_sub(1)
    } else {
        Some(index + 1)
    };
    let mut order = neighbours;
    if let Some(other) = other.filter(|o| *o < order.len()) {
        order.swap(index, other);
    }
    // Renumber the whole group so earlier ties can't undo the move.
    for (position, id) in order.iter().enumerate() {
        tx.execute(
            "UPDATE goals SET position = ?2 WHERE id = ?1",
            params![id, position as i64],
        )?;
    }
    tx.commit()?;
    Ok(())
}

pub fn categories(conn: &Connection) -> Result<Vec<String>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT DISTINCT category FROM goals WHERE category IS NOT NULL ORDER BY category COLLATE NOCASE",
    )?;
    let names = stmt
        .query_map([], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    Ok(names)
}

/// Renames a folder. Merging into an existing folder name is allowed.
pub fn rename_category(conn: &Connection, from: &str, to: &str) -> Result<(), CoreError> {
    let to = clean_title(to)?;
    conn.execute(
        "UPDATE goals SET category = ?2 WHERE category = ?1",
        params![from, to],
    )?;
    // Keep the folder's place in the user's order.
    let order: Vec<String> = folder_order(conn)
        .into_iter()
        .map(|n| if n == from { to.clone() } else { n })
        .collect();
    set_folder_order(conn, &order)
}

const FOLDER_ORDER_KEY: &str = "folder_order";

/// Folder names in the order the user chose, one per line in `meta`.
pub fn folder_order(conn: &Connection) -> Vec<String> {
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key = ?1",
            params![FOLDER_ORDER_KEY],
            |r| r.get(0),
        )
        .optional()
        .ok()
        .flatten();
    let mut seen = std::collections::BTreeSet::new();
    value
        .unwrap_or_default()
        .lines()
        .filter(|l| !l.is_empty() && seen.insert(l.to_string()))
        .map(String::from)
        .collect()
}

pub fn set_folder_order(conn: &Connection, names: &[String]) -> Result<(), CoreError> {
    let cleaned: Vec<&str> = names
        .iter()
        .map(|n| n.trim())
        .filter(|n| !n.is_empty() && !n.contains('\n'))
        .collect();
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2)
         ON CONFLICT (key) DO UPDATE SET value = excluded.value",
        params![FOLDER_ORDER_KEY, cleaned.join("\n")],
    )?;
    Ok(())
}

fn loggable_goal(conn: &Connection, goal_id: &str) -> Result<Goal, CoreError> {
    let goal = get_goal(conn, goal_id)?;
    if goal.archived {
        return Err(invalid("Restore this goal before logging it"));
    }
    Ok(goal)
}

fn insert_entry(
    conn: &Connection,
    goal: &Goal,
    date: &str,
    amount: f64,
    single_per_day: bool,
) -> Result<(), CoreError> {
    conn.execute(
        "INSERT INTO entries (id, goal_id, local_date, amount, single_per_day, seq)
         VALUES (?1, ?2, ?3, ?4, ?5, (SELECT COALESCE(MAX(seq), 0) + 1 FROM entries))",
        params![new_id(), goal.id, date, amount, single_per_day],
    )?;
    Ok(())
}

/// Checks that `day` can be logged: not in the future and not before the goal began.
fn check_log_day(goal: &Goal, day: NaiveDate, today: NaiveDate) -> Result<(), CoreError> {
    if day > today {
        return Err(invalid("You can't log a day that hasn't happened yet"));
    }
    if day < parse_date(&goal.created_date)? {
        return Err(invalid("You can't log a day before the goal was created"));
    }
    Ok(())
}

/// Ticks a yes/no goal or habit on `day`, or removes the tick if it's already there.
/// `day` is today for a normal tick, or an earlier day when filling in a missed one.
pub fn toggle_check_in(
    conn: &mut Connection,
    goal_id: &str,
    day: &str,
    today: &str,
) -> Result<LogResult, CoreError> {
    let date = parse_date(day)?;
    let today_date = parse_date(today)?;
    let tx = conn.transaction()?;
    let goal = loggable_goal(&tx, goal_id)?;
    check_log_day(&goal, date, today_date)?;
    // A yes/no tick can be undone on a later day. A habit's tick belongs to one day.
    let existing: Option<String> = match goal.kind {
        GoalKind::Numeric => return Err(invalid("Numeric goals log an amount")),
        GoalKind::YesNo => tx
            .query_row(
                "SELECT local_date FROM entries WHERE goal_id = ?1",
                params![goal_id],
                |r| r.get(0),
            )
            .optional()?,
        GoalKind::Habit => tx
            .query_row(
                "SELECT local_date FROM entries WHERE goal_id = ?1 AND local_date = ?2",
                params![goal_id, day],
                |r| r.get(0),
            )
            .optional()?,
    };
    let result = match existing {
        Some(logged) => {
            tx.execute(
                "DELETE FROM entries WHERE goal_id = ?1 AND local_date = ?2",
                params![goal_id, logged],
            )?;
            sync_targets(&tx, &goal, parse_date(&logged)?, today)?
        }
        None => {
            insert_entry(&tx, &goal, day, 1.0, true)?;
            sync_targets(&tx, &goal, date, today)?
        }
    };
    tx.commit()?;
    Ok(result)
}

pub fn log_amount(
    conn: &mut Connection,
    goal_id: &str,
    amount: f64,
    day: &str,
    today: &str,
) -> Result<LogResult, CoreError> {
    let date = parse_date(day)?;
    let today_date = parse_date(today)?;
    if !amount.is_finite() || amount <= 0.0 {
        return Err(invalid("Enter an amount greater than zero"));
    }
    let tx = conn.transaction()?;
    let goal = loggable_goal(&tx, goal_id)?;
    if goal.kind != GoalKind::Numeric {
        return Err(invalid("Only numeric goals log an amount"));
    }
    check_log_day(&goal, date, today_date)?;
    insert_entry(&tx, &goal, day, amount, false)?;
    let result = sync_targets(&tx, &goal, date, today)?;
    tx.commit()?;
    Ok(result)
}

pub fn delete_entry(conn: &mut Connection, entry_id: &str, today: &str) -> Result<(), CoreError> {
    parse_date(today)?;
    let tx = conn.transaction()?;
    let found: Option<(String, String)> = tx
        .query_row(
            "SELECT goal_id, local_date FROM entries WHERE id = ?1",
            params![entry_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((goal_id, logged)) = found else {
        return Ok(());
    };
    tx.execute("DELETE FROM entries WHERE id = ?1", params![entry_id])?;
    let goal = get_goal(&tx, &goal_id)?;
    sync_targets(&tx, &goal, parse_date(&logged)?, today)?;
    tx.commit()?;
    Ok(())
}

pub fn list_entries(conn: &Connection, goal_id: &str, limit: u32) -> Result<Vec<Entry>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, goal_id, local_date, amount FROM entries WHERE goal_id = ?1
         ORDER BY local_date DESC, seq DESC LIMIT ?2",
    )?;
    let entries = stmt
        .query_map(params![goal_id, limit], |r| {
            Ok(Entry {
                id: r.get(0)?,
                goal_id: r.get(1)?,
                local_date: r.get(2)?,
                amount: r.get(3)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(entries)
}

/// Total logged per period, keyed by the period's first day.
pub fn period_totals(
    conn: &Connection,
    goal_id: &str,
    period: Period,
) -> Result<BTreeMap<NaiveDate, f64>, CoreError> {
    let mut stmt = conn.prepare("SELECT local_date, amount FROM entries WHERE goal_id = ?1")?;
    let rows = stmt.query_map(params![goal_id], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, f64>(1)?))
    })?;
    let week = week_start(conn);
    let mut totals = BTreeMap::new();
    for row in rows {
        let (date, amount) = row?;
        *totals
            .entry(period_start_ws(parse_date(&date)?, period, week))
            .or_insert(0.0) += amount;
    }
    Ok(totals)
}

/// Periods (by first day) that contain a skipped day.
pub fn excused_periods(
    conn: &Connection,
    goal_id: &str,
    period: Period,
) -> Result<std::collections::BTreeSet<NaiveDate>, CoreError> {
    let mut stmt = conn.prepare("SELECT local_date FROM skips WHERE goal_id = ?1")?;
    let rows = stmt.query_map(params![goal_id], |r| r.get::<_, String>(0))?;
    let week = week_start(conn);
    let mut periods = std::collections::BTreeSet::new();
    for row in rows {
        periods.insert(period_start_ws(parse_date(&row?)?, period, week));
    }
    Ok(periods)
}

/// Longest pause that can be set in one go.
const MAX_PAUSE_DAYS: i64 = 366;

/// Marks every day from `from` to `to` (inclusive) as skipped. A pause can reach into
/// the future, such as a holiday, but can't start before the goal was created.
pub fn pause_goal(
    conn: &mut Connection,
    goal_id: &str,
    from: &str,
    to: &str,
) -> Result<(), CoreError> {
    let (start, end) = (parse_date(from)?, parse_date(to)?);
    if end < start {
        return Err(invalid("The pause must end on or after the day it starts"));
    }
    if (end - start).num_days() >= MAX_PAUSE_DAYS {
        return Err(invalid("A pause can last at most a year"));
    }
    let tx = conn.transaction()?;
    let goal = get_goal(&tx, goal_id)?;
    if start < parse_date(&goal.created_date)? {
        return Err(invalid("You can't pause days before the goal was created"));
    }
    let mut day = start;
    while day <= end {
        tx.execute(
            "INSERT OR IGNORE INTO skips (goal_id, local_date) VALUES (?1, ?2)",
            params![goal_id, day.to_string()],
        )?;
        day = day.succ_opt().expect("date in range");
    }
    tx.commit()?;
    Ok(())
}

/// Removes skipped days from `from` to `to` (inclusive).
pub fn unpause_goal(
    conn: &Connection,
    goal_id: &str,
    from: &str,
    to: &str,
) -> Result<(), CoreError> {
    let (start, end) = (parse_date(from)?, parse_date(to)?);
    conn.execute(
        "DELETE FROM skips WHERE goal_id = ?1 AND local_date BETWEEN ?2 AND ?3",
        params![goal_id, start.to_string(), end.to_string()],
    )?;
    Ok(())
}

/// Skipped days for a goal, oldest first.
pub fn skipped_days(conn: &Connection, goal_id: &str) -> Result<Vec<String>, CoreError> {
    let mut stmt =
        conn.prepare("SELECT local_date FROM skips WHERE goal_id = ?1 ORDER BY local_date")?;
    let days = stmt
        .query_map(params![goal_id], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    Ok(days)
}

/// Records targets that are now met and removes targets that no longer are.
/// `date` is the logged day that changed, which decides which period to check.
fn sync_targets(
    conn: &Connection,
    goal: &Goal,
    date: NaiveDate,
    today: &str,
) -> Result<LogResult, CoreError> {
    let mut result = LogResult::default();
    let goal = &get_goal(conn, &goal.id)?; // Totals must include the change just made.
    if goal.avoid {
        // A quit habit's days pass without any action, so there is no moment to roll a
        // reward and nothing to undo. Slips only change the streak.
        return Ok(result);
    }

    if goal.kind == GoalKind::YesNo {
        sync_target(conn, goal, "done", goal.done, false, today, &mut result)?;
    }
    if let (Some(period), Some(target)) = (goal.period, goal.period_target) {
        let totals = period_totals(conn, &goal.id, period)?;
        let week = week_start(conn);
        let start = period_start_ws(date, period, week);
        let met = |p: NaiveDate| totals.get(&p).is_some_and(|t| t + EPSILON >= target);
        let creation = period_start_ws(parse_date(&goal.created_date)?, period, week);
        let mut previous = previous_start(start, period);
        // Days off don't count as missed, so look back to the last day that was due.
        while period == Period::Day && previous > creation && !is_scheduled(goal.weekdays, previous)
        {
            previous = previous_start(previous, period);
        }
        let comeback = previous > creation && !met(previous);
        sync_target(
            conn,
            goal,
            &format!("p:{start}"),
            met(start),
            comeback,
            today,
            &mut result,
        )?;
    }
    if let Some(target) = goal.total_target {
        let met = goal.total_logged + EPSILON >= target;
        sync_target(conn, goal, "total", met, false, today, &mut result)?;
    }
    Ok(result)
}

fn sync_target(
    conn: &Connection,
    goal: &Goal,
    key: &str,
    met: bool,
    comeback: bool,
    today: &str,
    result: &mut LogResult,
) -> Result<(), CoreError> {
    let recorded: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM target_completions WHERE goal_id = ?1 AND target_key = ?2)",
        params![goal.id, key],
        |r| r.get(0),
    )?;
    if met && !recorded {
        match rewards::complete_target(conn, &goal.id, key, comeback, today)? {
            rewards::Outcome::JarEmpty => result.jar_empty = true,
            rewards::Outcome::NoTreat => result.no_treat += 1,
            rewards::Outcome::Draw => result.drew += 1,
            rewards::Outcome::RewardsOff => {}
        }
    } else if !met && recorded {
        let had_treat: bool = conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM reward_draws d
                JOIN target_completions c ON c.id = d.completion_id
                WHERE c.goal_id = ?1 AND c.target_key = ?2)",
            params![goal.id, key],
            |r| r.get(0),
        )?;
        if had_treat {
            result.treats_removed += 1;
        }
        // Removing the completion also removes any reward it earned.
        conn.execute(
            "DELETE FROM target_completions WHERE goal_id = ?1 AND target_key = ?2",
            params![goal.id, key],
        )?;
    }
    Ok(())
}

pub fn list_milestones(conn: &Connection, goal_id: &str) -> Result<Vec<Milestone>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, goal_id, title, done_date FROM milestones WHERE goal_id = ?1 ORDER BY position",
    )?;
    let milestones = stmt
        .query_map(params![goal_id], |r| {
            Ok(Milestone {
                id: r.get(0)?,
                goal_id: r.get(1)?,
                title: r.get(2)?,
                done_date: r.get(3)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(milestones)
}

pub fn add_milestone(
    conn: &mut Connection,
    goal_id: &str,
    title: &str,
    today: &str,
) -> Result<(), CoreError> {
    parse_date(today)?;
    let title = clean_title(title)?;
    get_goal(conn, goal_id)?;
    let milestone_id = new_id();
    let tx = conn.transaction()?;
    tx.execute(
        "INSERT INTO milestones (id, goal_id, title, position)
         VALUES (?1, ?2, ?3, (SELECT COALESCE(MAX(position), -1) + 1 FROM milestones WHERE goal_id = ?2))",
        params![milestone_id, goal_id, title],
    )?;
    crate::tasks::create_milestone_task(&tx, goal_id, &milestone_id, &title, today)?;
    tx.commit()?;
    Ok(())
}

fn milestone_goal(conn: &Connection, milestone_id: &str) -> Result<Option<String>, CoreError> {
    Ok(conn
        .query_row(
            "SELECT goal_id FROM milestones WHERE id = ?1",
            params![milestone_id],
            |r| r.get(0),
        )
        .optional()?)
}

pub fn toggle_milestone(
    conn: &mut Connection,
    milestone_id: &str,
    today: &str,
) -> Result<LogResult, CoreError> {
    parse_date(today)?;
    let tx = conn.transaction()?;
    let Some(goal_id) = milestone_goal(&tx, milestone_id)? else {
        return Err(invalid("That milestone no longer exists"));
    };
    let goal = loggable_goal(&tx, &goal_id)?;
    let done: Option<String> = tx.query_row(
        "SELECT done_date FROM milestones WHERE id = ?1",
        params![milestone_id],
        |r| r.get(0),
    )?;
    let done_date = if done.is_some() { None } else { Some(today) };
    tx.execute(
        "UPDATE milestones SET done_date = ?2 WHERE id = ?1",
        params![milestone_id, done_date],
    )?;
    // The task that mirrors this milestone shows the same state.
    tx.execute(
        "UPDATE tasks SET done_date = ?2 WHERE milestone_id = ?1",
        params![milestone_id, done_date],
    )?;
    let mut result = LogResult::default();
    sync_target(
        &tx,
        &goal,
        &format!("m:{milestone_id}"),
        done.is_none(),
        false,
        today,
        &mut result,
    )?;
    tx.commit()?;
    Ok(result)
}

pub fn delete_milestone(conn: &mut Connection, milestone_id: &str) -> Result<(), CoreError> {
    let tx = conn.transaction()?;
    if let Some(goal_id) = milestone_goal(&tx, milestone_id)? {
        tx.execute(
            "DELETE FROM target_completions WHERE goal_id = ?1 AND target_key = ?2",
            params![goal_id, format!("m:{milestone_id}")],
        )?;
        tx.execute(
            "DELETE FROM milestones WHERE id = ?1",
            params![milestone_id],
        )?;
    }
    tx.commit()?;
    Ok(())
}

/// Moves a milestone one place earlier or later in its goal's order.
pub fn move_milestone(
    conn: &mut Connection,
    milestone_id: &str,
    earlier: bool,
) -> Result<(), CoreError> {
    let tx = conn.transaction()?;
    let Some(goal_id) = milestone_goal(&tx, milestone_id)? else {
        return Ok(());
    };
    let ids: Vec<String> = {
        let mut stmt =
            tx.prepare("SELECT id FROM milestones WHERE goal_id = ?1 ORDER BY position")?;
        stmt.query_map(params![goal_id], |r| r.get(0))?
            .collect::<Result<_, _>>()?
    };
    let index = ids.iter().position(|id| id == milestone_id);
    let other = index.and_then(|i| {
        if earlier {
            i.checked_sub(1)
        } else {
            Some(i + 1)
        }
    });
    if let (Some(index), Some(other)) = (index, other)
        && other < ids.len()
    {
        let mut order = ids;
        order.swap(index, other);
        for (position, id) in order.iter().enumerate() {
            tx.execute(
                "UPDATE milestones SET position = ?2 WHERE id = ?1",
                params![id, position as i64],
            )?;
        }
    }
    tx.commit()?;
    Ok(())
}

pub fn today_items(conn: &Connection, today: &str) -> Result<Vec<TodayItem>, CoreError> {
    let date = parse_date(today)?;
    // Order never depends on progress, so cards don't move while being ticked.
    let mut items = Vec::new();
    for goal in list_goals(conn, false)? {
        if let Some(item) = today_item(conn, &goal, date, today)? {
            items.push(item);
        }
    }
    Ok(items)
}

fn today_item(
    conn: &Connection,
    goal: &Goal,
    date: NaiveDate,
    today: &str,
) -> Result<Option<TodayItem>, CoreError> {
    let logged_today: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM entries WHERE goal_id = ?1 AND local_date = ?2)",
        params![goal.id, today],
        |r| r.get(0),
    )?;

    let (mut period_progress, mut period_met, mut current_streak) = (0.0, false, 0);
    if let (Some(period), Some(target)) = (goal.period, goal.period_target) {
        let week = week_start(conn);
        let mut totals = period_totals(conn, &goal.id, period)?;
        if goal.avoid {
            totals = stats::clean_days(&totals, parse_date(&goal.created_date)?, date);
        }
        period_progress = totals
            .get(&period_start_ws(date, period, week))
            .copied()
            .unwrap_or(0.0);
        period_met = period_progress + EPSILON >= target;
        let excused = excused_periods(conn, &goal.id, period)?;
        current_streak = stats::period_summary_ws(
            parse_date(&goal.created_date)?,
            period,
            target,
            &totals,
            &excused,
            goal.weekdays,
            date,
            week,
            goal.avoid,
        )
        .current_streak;
    }

    let needs_work = match goal.kind {
        // A quit habit stays on Today all day, so a slip can be logged from it.
        GoalKind::Habit if goal.avoid => true,
        GoalKind::YesNo => !goal.done,
        GoalKind::Habit => !period_met && is_scheduled(goal.weekdays, date),
        // A numeric goal with no targets is a plain tally, which is always open.
        GoalKind::Numeric => !goal.done && (goal.period.is_none() || !period_met),
    };
    let skipped_today: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM skips WHERE goal_id = ?1 AND local_date = ?2)",
        params![goal.id, today],
        |r| r.get(0),
    )?;
    if (!needs_work || skipped_today) && !logged_today {
        return Ok(None);
    }

    let next_milestone = conn
        .query_row(
            "SELECT title FROM milestones WHERE goal_id = ?1 AND done_date IS NULL ORDER BY position LIMIT 1",
            params![goal.id],
            |r| r.get(0),
        )
        .optional()?;
    let complete = match goal.kind {
        GoalKind::Habit if goal.avoid => !logged_today,
        GoalKind::Habit => period_met || logged_today,
        _ => !needs_work,
    };
    Ok(Some(TodayItem {
        goal_id: goal.id.clone(),
        title: goal.title.clone(),
        kind: goal.kind,
        category: goal.category.clone(),
        unit: goal.unit.clone(),
        logged_today,
        period: goal.period,
        period_progress,
        period_target: goal.period_target,
        total_progress: goal.total_logged,
        total_target: goal.total_target,
        complete,
        deadline: goal.deadline.clone(),
        overdue: !goal.done && goal.deadline.as_deref().is_some_and(|d| d < today),
        current_streak,
        next_milestone,
        milestones_done: goal.milestones_done,
        milestones_total: goal.milestones_total,
        avoid: goal.avoid,
    }))
}

/// One day per step in `from..=to`, for one goal or all active goals.
pub fn heatmap(
    conn: &Connection,
    goal_id: Option<&str>,
    from: &str,
    to: &str,
) -> Result<Vec<HeatmapDay>, CoreError> {
    let (start, end) = (parse_date(from)?, parse_date(to)?);
    if start > end || (end - start).num_days() > 800 {
        return Err(invalid("Choose a date range of up to 800 days"));
    }
    let mut stmt = conn.prepare(
        "SELECT e.local_date, COUNT(*) FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE e.local_date BETWEEN ?2 AND ?3
           AND (?1 IS NOT NULL AND e.goal_id = ?1 OR ?1 IS NULL AND g.archived = 0)
         GROUP BY e.local_date",
    )?;
    let counts: BTreeMap<String, u32> = stmt
        .query_map(params![goal_id, from, to], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let mut days = Vec::new();
    let mut day = start;
    while day <= end {
        let date = day.to_string();
        let count = counts.get(&date).copied().unwrap_or(0);
        days.push(HeatmapDay { date, count });
        day = next_start(day, Period::Day);
    }
    Ok(days)
}
