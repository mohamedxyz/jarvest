//! The day score: how much of today's plan is done, as points, a percent and a level.
//! It only reads data and `today` comes from the caller, so results never depend on the clock.

use rusqlite::Connection;

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::today_items;
use crate::model::{DayLevel, DayScore};

/// Points for one goal or habit card.
const GOAL_POINTS: u32 = 10;

/// Points for a task by its priority text in the database.
fn task_points(priority: &str) -> u32 {
    match priority {
        "high" => 15,
        "low" => 5,
        _ => 10,
    }
}

/// The level for a percent of today's points, given that at least one point is possible.
fn level_for(earned: u32, percent: u32) -> DayLevel {
    match (earned, percent) {
        (0, _) => DayLevel::None,
        (_, 100..) => DayLevel::Titan,
        (_, 50..) => DayLevel::Blaze,
        _ => DayLevel::Spark,
    }
}

/// Points still needed to reach the next level. 0 when already at the top (or nothing planned).
fn points_to_next(earned: u32, possible: u32, level: DayLevel) -> u32 {
    match level {
        DayLevel::None => 1,
        // Blaze starts at half of the points, rounded up.
        DayLevel::Spark => possible.div_ceil(2).saturating_sub(earned).max(1),
        DayLevel::Blaze => possible - earned,
        DayLevel::Titan => 0,
    }
}

pub fn day_score(conn: &Connection, today: &str) -> Result<DayScore, CoreError> {
    parse_date(today)?;
    let (mut earned, mut possible) = (0u32, 0u32);
    let (mut goals_done, mut goals_total) = (0u32, 0u32);
    let (mut tasks_done, mut tasks_total) = (0u32, 0u32);

    for item in today_items(conn, today)? {
        goals_total += 1;
        possible += GOAL_POINTS;
        if item.complete {
            goals_done += 1;
            earned += GOAL_POINTS;
        }
    }

    // Finished today, then still open and due. Milestone tasks are counted on their goal.
    let mut done = conn.prepare(
        "SELECT priority FROM tasks
         WHERE done_date = ?1 AND deleted_date IS NULL AND milestone_id IS NULL",
    )?;
    for priority in done.query_map([today], |r| r.get::<_, String>(0))? {
        let points = task_points(&priority?);
        tasks_done += 1;
        tasks_total += 1;
        earned += points;
        possible += points;
    }
    let mut open = conn.prepare(
        "SELECT priority FROM tasks
         WHERE done_date IS NULL AND due IS NOT NULL AND due <= ?1
           AND deleted_date IS NULL AND archived_date IS NULL AND milestone_id IS NULL",
    )?;
    for priority in open.query_map([today], |r| r.get::<_, String>(0))? {
        tasks_total += 1;
        possible += task_points(&priority?);
    }

    // Zero planned points gives a percent of 0.
    let percent = (earned * 100).checked_div(possible).unwrap_or(0);
    let level = if possible == 0 {
        DayLevel::None
    } else {
        level_for(earned, percent)
    };
    Ok(DayScore {
        earned,
        possible,
        percent,
        level,
        goals_done,
        goals_total,
        tasks_done,
        tasks_total,
        points_to_next: if possible == 0 {
            0
        } else {
            points_to_next(earned, possible, level)
        },
    })
}
