//! Badges are worked out from existing data each time, so they need no table and
//! can never fall out of step with undo.

use rusqlite::Connection;

use crate::CoreError;
use crate::model::Badge;
use crate::stats;

fn count(conn: &Connection, sql: &str) -> Result<u32, CoreError> {
    Ok(conn.query_row(sql, [], |r| r.get(0))?)
}

pub fn badges(conn: &Connection, today: &str) -> Result<Vec<Badge>, CoreError> {
    // Goals in the trash don't count, so a badge can't depend on something hidden.
    let entries = count(
        conn,
        "SELECT COUNT(*) FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE g.deleted_date IS NULL",
    )?;
    let targets = count(
        conn,
        "SELECT COUNT(*) FROM target_completions c JOIN goals g ON g.id = c.goal_id
         WHERE g.deleted_date IS NULL",
    )?;
    let draws = count(
        conn,
        "SELECT COUNT(*) FROM reward_draws d
         JOIN target_completions c ON c.id = d.completion_id
         JOIN goals g ON g.id = c.goal_id
         WHERE g.deleted_date IS NULL",
    )?;
    let finished = count(
        conn,
        "SELECT COUNT(*) FROM target_completions c JOIN goals g ON g.id = c.goal_id
         WHERE g.deleted_date IS NULL AND c.target_key IN ('done', 'total')",
    )?;
    let best_streak = stats::overview(conn, today)?
        .goals
        .iter()
        .map(|g| g.best_streak)
        .max()
        .unwrap_or(0);

    let titan_days = count(conn, "SELECT COUNT(*) FROM day_scores WHERE level = 'titan'")?;
    let focus_minutes = count(conn, "SELECT COALESCE(SUM(minutes), 0) FROM focus_sessions")?;
    let events = count(conn, "SELECT COUNT(*) FROM events")?;
    let bosses = count(conn, "SELECT COUNT(*) FROM weekly_bosses WHERE defeated_date IS NOT NULL")?;
    let blaze_streak = crate::progress::day_records(conn, today)?.longest_blaze_streak;

    let list = [
        ("first_log", "First step", "Log anything once", entries >= 1),
        ("logs_10", "Warming up", "Log 10 times", entries >= 10),
        ("logs_100", "Centurion", "Log 100 times", entries >= 100),
        ("logs_500", "Unstoppable", "Log 500 times", entries >= 500),
        (
            "streak_7",
            "One week strong",
            "Reach a 7 period streak",
            best_streak >= 7,
        ),
        (
            "streak_30",
            "Habit formed",
            "Reach a 30 period streak",
            best_streak >= 30,
        ),
        (
            "streak_100",
            "Legend",
            "Reach a 100 period streak",
            best_streak >= 100,
        ),
        ("targets_10", "On target", "Hit 10 targets", targets >= 10),
        (
            "targets_100",
            "Sharpshooter",
            "Hit 100 targets",
            targets >= 100,
        ),
        ("finished_1", "Finisher", "Finish a goal", finished >= 1),
        ("finished_5", "Closer", "Finish 5 goals", finished >= 5),
        ("draw_1", "Lucky draw", "Win a treat", draws >= 1),
        ("draw_10", "Treat collector", "Win 10 treats", draws >= 10),
        ("titan_1", "Titan day", "Finish everything planned in a day", titan_days >= 1),
        ("titan_10", "Ten Titans", "Have 10 Titan days", titan_days >= 10),
        ("blaze_7", "Seven blazing days", "Reach Blaze or better 7 days in a row", blaze_streak >= 7),
        ("boss_1", "Boss slayer", "Beat a weekly boss", bosses >= 1),
        ("focus_60", "Deep focus", "Focus for 60 minutes in total", focus_minutes >= 60),
        ("focus_1000", "Flow state", "Focus for 1000 minutes in total", focus_minutes >= 1000),
        ("planner_5", "Planner", "Add 5 events to the calendar", events >= 5),
    ];
    Ok(list
        .into_iter()
        .map(|(id, title, description, earned)| Badge {
            id: id.into(),
            title: title.into(),
            description: description.into(),
            earned,
        })
        .collect())
}
