//! Database connection setup and schema migrations.

use rusqlite::Connection;

use crate::CoreError;

/// Identifies a SQLite file as ours ("GOAL" in ASCII).
pub const APPLICATION_ID: i32 = 0x474F_414C;

/// One entry per schema version. Never edit a released migration: append one.
const MIGRATIONS: &[&str] = &[
    include_str!("sql/001_init.sql"),
    include_str!("sql/002_skips.sql"),
    include_str!("sql/003_weekdays.sql"),
    include_str!("sql/004_goal_order.sql"),
    include_str!("sql/005_reminders.sql"),
    include_str!("sql/006_tasks.sql"),
    include_str!("sql/007_task_goal.sql"),
    include_str!("sql/008_milestone_tasks.sql"),
    include_str!("sql/009_task_archive.sql"),
    include_str!("sql/010_icons_and_samples.sql"),
    include_str!("sql/011_avoid_habits.sql"),
    include_str!("sql/012_goal_trash.sql"),
    include_str!("sql/013_monsters.sql"),
    include_str!("sql/014_progress.sql"),
    include_str!("sql/015_calendar.sql"),
    include_str!("sql/016_calendar_events.sql"),
    include_str!("sql/017_task_templates.sql"),
    include_str!("sql/018_focus.sql"),
    include_str!("sql/019_xp_bonuses.sql"),
    include_str!("sql/020_holidays.sql"),
    include_str!("sql/021_day_notes.sql"),
    include_str!("sql/022_week_review.sql"),
    include_str!("sql/023_routines.sql"),
];

pub const SCHEMA_VERSION: i32 = MIGRATIONS.len() as i32;

/// The schema version that added `goals.deleted_date`. It is looked up by the file's
/// text, so it stays right even when other migrations are numbered before it.
pub fn goal_trash_version() -> i32 {
    let ours = include_str!("sql/012_goal_trash.sql");
    MIGRATIONS
        .iter()
        .position(|sql| *sql == ours)
        .map_or(SCHEMA_VERSION, |index| index as i32 + 1)
}

pub fn open(path: &str) -> Result<Connection, CoreError> {
    let mut conn = Connection::open(path)?;
    configure(&conn)?;
    migrate(&mut conn)?;
    Ok(conn)
}

fn configure(conn: &Connection) -> Result<(), CoreError> {
    conn.execute_batch(
        "PRAGMA journal_mode = WAL;
         PRAGMA foreign_keys = ON;
         PRAGMA trusted_schema = OFF;",
    )?;
    Ok(())
}

/// Applies every migration newer than the file's `user_version`.
pub fn migrate(conn: &mut Connection) -> Result<(), CoreError> {
    migrate_to(conn, SCHEMA_VERSION)
}

/// Applies migrations up to `version`. Backup validation uses this to rebuild the
/// exact schema an older file should have.
pub fn migrate_to(conn: &mut Connection, version: i32) -> Result<(), CoreError> {
    let current: i32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if current > SCHEMA_VERSION {
        return Err(CoreError::InvalidBackup {
            reason: "this data was created by a newer version of the app".into(),
        });
    }
    let pending = MIGRATIONS
        .iter()
        .enumerate()
        .take(version as usize)
        .skip(current as usize);
    for (index, sql) in pending {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.pragma_update(None, "application_id", APPLICATION_ID)?;
        tx.pragma_update(None, "user_version", (index + 1) as i32)?;
        tx.commit()?;
    }
    Ok(())
}
