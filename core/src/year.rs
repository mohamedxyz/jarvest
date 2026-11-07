//! Year in review: one year of activity at a glance. It only reads data.

use std::collections::BTreeSet;

use chrono::NaiveDate;
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::invalid;
use crate::model::*;
use crate::rewards;

/// The year's numbers, from January 1 up to today (or December 31 for a past year).
pub fn year_review(conn: &Connection, year: i32, today: &str) -> Result<YearReview, CoreError> {
    let today = parse_date(today)?;
    let first =
        NaiveDate::from_ymd_opt(year, 1, 1).ok_or_else(|| invalid("Choose a valid year"))?;
    if first > today {
        return Err(invalid("That year hasn't started yet"));
    }
    let last = NaiveDate::from_ymd_opt(year, 12, 31)
        .expect("December 31 exists")
        .min(today);
    let (from, to) = (first.to_string(), last.to_string());
    let count = |sql: &str| -> Result<u32, CoreError> {
        Ok(conn.query_row(sql, params![from, to], |r| r.get(0))?)
    };

    // Logs on goals that are not in the trash. Slips on quit habits are not progress.
    let logs = count(
        "SELECT COUNT(*) FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE e.local_date BETWEEN ?1 AND ?2 AND g.deleted_date IS NULL AND g.avoid = 0",
    )?;
    // A period target belongs to its period ("p:2025-12-31"), even when it was filled in
    // later; other targets count on the day they were met.
    let targets_met = count(
        "SELECT COUNT(*) FROM target_completions c JOIN goals g ON g.id = c.goal_id
         WHERE CASE WHEN c.target_key LIKE 'p:%' THEN substr(c.target_key, 3) ELSE c.local_date END
               BETWEEN ?1 AND ?2
           AND g.deleted_date IS NULL",
    )?;
    // Milestone tasks are left out: the milestone already counts as a target.
    let tasks_done = count(
        "SELECT COUNT(*) FROM tasks WHERE done_date BETWEEN ?1 AND ?2
           AND deleted_date IS NULL AND milestone_id IS NULL",
    )?;
    let xp = count(
        "SELECT (SELECT COALESCE(SUM(earned), 0) FROM day_scores WHERE local_date BETWEEN ?1 AND ?2)
              + (SELECT COALESCE(SUM(points), 0) FROM xp_bonuses WHERE local_date BETWEEN ?1 AND ?2)",
    )?;
    let titan_days = count(
        "SELECT COUNT(*) FROM day_scores WHERE level = 'titan' AND local_date BETWEEN ?1 AND ?2",
    )?;
    let treats_bought =
        count("SELECT COUNT(*) FROM shop_purchases WHERE local_date BETWEEN ?1 AND ?2")?;
    let in_year = |d: &str| d >= from.as_str() && d <= to.as_str();
    let treats_won = rewards::list_draws(conn)?
        .iter()
        .filter(|d| !d.from_shop && in_year(&d.local_date))
        .count() as u32;

    // Active days: any log (slips aside) or finished task.
    let mut stmt = conn.prepare(
        "SELECT e.local_date FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE e.local_date BETWEEN ?1 AND ?2 AND g.deleted_date IS NULL AND g.avoid = 0
         UNION
         SELECT done_date FROM tasks WHERE done_date BETWEEN ?1 AND ?2 AND deleted_date IS NULL",
    )?;
    let days: BTreeSet<NaiveDate> = stmt
        .query_map(params![from, to], |r| r.get::<_, String>(0))?
        .filter_map(|d| d.ok().and_then(|d| parse_date(&d).ok()))
        .collect();
    let (mut longest_run, mut run, mut previous) = (0u32, 0u32, None::<NaiveDate>);
    for day in &days {
        run = match previous {
            Some(p) if p.succ_opt() == Some(*day) => run + 1,
            _ => 1,
        };
        longest_run = longest_run.max(run);
        previous = Some(*day);
    }

    let top: Option<(String, u32)> = conn
        .query_row(
            "SELECT g.title, COUNT(*) AS n FROM entries e JOIN goals g ON g.id = e.goal_id
             WHERE e.local_date BETWEEN ?1 AND ?2 AND g.deleted_date IS NULL AND g.avoid = 0
             GROUP BY g.id ORDER BY n DESC, g.title LIMIT 1",
            params![from, to],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok();
    Ok(YearReview {
        year,
        logs,
        active_days: days.len() as u32,
        longest_run,
        targets_met,
        tasks_done,
        treats_won,
        treats_bought,
        xp,
        titan_days,
        top_logs: top.as_ref().map_or(0, |t| t.1),
        top_title: top.map(|t| t.0),
    })
}
