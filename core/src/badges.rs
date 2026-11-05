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
    let entries = count(conn, "SELECT COUNT(*) FROM entries")?;
    let targets = count(conn, "SELECT COUNT(*) FROM target_completions")?;
    let draws = count(conn, "SELECT COUNT(*) FROM reward_draws")?;
    let finished = count(
        conn,
        "SELECT COUNT(*) FROM target_completions WHERE target_key IN ('done', 'total')",
    )?;
    let best_streak = stats::overview(conn, today)?
        .goals
        .iter()
        .map(|g| g.best_streak)
        .max()
        .unwrap_or(0);

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
