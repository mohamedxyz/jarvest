//! Single-file backup and restore. A backup file is untrusted input, so it's
//! validated in full on a private copy before current data is touched.

use std::collections::BTreeSet;
use std::fs;
use std::path::Path;

use rusqlite::{Connection, OpenFlags, params};

use crate::CoreError;
use crate::db::{self, APPLICATION_ID, SCHEMA_VERSION};
use crate::rewards;

fn bad(reason: impl Into<String>) -> CoreError {
    CoreError::InvalidBackup {
        reason: reason.into(),
    }
}

fn io_error(e: std::io::Error) -> CoreError {
    CoreError::Database {
        detail: e.to_string(),
    }
}

/// Writes a consistent copy of the database. `VACUUM INTO` includes changes still
/// in the write-ahead log, which copying the live file could miss.
pub fn export(conn: &Connection, dest: &str) -> Result<(), CoreError> {
    if Path::new(dest).exists() {
        fs::remove_file(dest).map_err(io_error)?;
    }
    conn.execute("VACUUM INTO ?1", params![dest])?;
    Ok(())
}

type SchemaObject = (String, String, Option<String>);

fn schema_of(conn: &Connection) -> Result<BTreeSet<SchemaObject>, CoreError> {
    let mut stmt =
        conn.prepare("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")?;
    let schema = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .collect::<Result<_, _>>()?;
    Ok(schema)
}

/// Looks at the first bytes of the file to give a clear message for the most common
/// problems (empty, not SQLite, or cut short) before SQLite itself is asked.
fn check_file_shape(path: &str) -> Result<(), CoreError> {
    use std::io::Read;
    let mut file = fs::File::open(path).map_err(io_error)?;
    let size = file.metadata().map_err(io_error)?.len();
    if size == 0 {
        return Err(bad("the file is empty"));
    }
    let mut header = [0u8; 100];
    if file.read_exact(&mut header).is_err() || &header[..16] != b"SQLite format 3\0" {
        return Err(bad("this isn't a backup file"));
    }
    // Bytes 16-17 hold the page size (1 means 65536). Bytes 28-31 hold the page count.
    let raw_page_size = u16::from_be_bytes([header[16], header[17]]) as u64;
    let page_size = if raw_page_size == 1 {
        65536
    } else {
        raw_page_size
    };
    let pages = u32::from_be_bytes([header[28], header[29], header[30], header[31]]) as u64;
    if pages > 0 && size < page_size * pages {
        return Err(bad(
            "the file is cut short, so the copy or download probably didn't finish",
        ));
    }
    Ok(())
}

/// Checks a file without changing it. Comparing the full schema with the one this
/// app would create rejects triggers, views, and altered tables.
pub fn validate(path: &str) -> Result<(), CoreError> {
    check_file_shape(path)?;
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn =
        Connection::open_with_flags(path, flags).map_err(|_| bad("this isn't a backup file"))?;
    conn.execute_batch("PRAGMA trusted_schema = OFF;")
        .map_err(|_| bad("this isn't a backup file"))?;

    let not_backup = |_| bad("this isn't a backup file");
    let id: i32 = conn
        .pragma_query_value(None, "application_id", |r| r.get(0))
        .map_err(not_backup)?;
    if id != APPLICATION_ID {
        return Err(bad("this isn't a Goal Tracker backup"));
    }
    let version: i32 = conn
        .pragma_query_value(None, "user_version", |r| r.get(0))
        .map_err(not_backup)?;
    if version > SCHEMA_VERSION {
        return Err(bad(
            "it was made by a newer version of the app, so update Jarvest and try again",
        ));
    }
    if version < 1 {
        return Err(bad("this isn't a Goal Tracker backup"));
    }

    let integrity: String = conn
        .query_row("PRAGMA integrity_check(1)", [], |r| r.get(0))
        .map_err(|_| bad("the file is damaged"))?;
    if integrity != "ok" {
        return Err(bad("the file is damaged"));
    }
    let mut reference = Connection::open_in_memory()?;
    db::migrate_to(&mut reference, version)?;
    if schema_of(&conn).map_err(|_| bad("the file is damaged"))? != schema_of(&reference)? {
        return Err(bad("its contents don't match what the app expects"));
    }
    let foreign_key_problem = conn
        .prepare("PRAGMA foreign_key_check")
        .and_then(|mut stmt| stmt.exists([]))
        .map_err(|_| bad("the file is damaged"))?;
    if foreign_key_problem {
        return Err(bad("the file is damaged"));
    }
    // The schema can't express date formats, and the rules rely on them.
    let bad_dates: i64 = conn
        .query_row(
            "SELECT
               (SELECT COUNT(*) FROM goals WHERE date(created_date) IS NOT created_date
                    OR (deadline IS NOT NULL AND date(deadline) IS NOT deadline))
             + (SELECT COUNT(*) FROM entries WHERE date(local_date) IS NOT local_date)
             + (SELECT COUNT(*) FROM target_completions WHERE date(local_date) IS NOT local_date)
             + (SELECT COUNT(*) FROM skips WHERE date(local_date) IS NOT local_date)
             + (SELECT COUNT(*) FROM milestones WHERE done_date IS NOT NULL AND date(done_date) IS NOT done_date)",
            [],
            |r| r.get(0),
        )
        .map_err(|_| bad("the file is damaged"))?;
    if bad_dates > 0 {
        return Err(bad("it contains invalid dates"));
    }
    // Tasks arrived in schema version 6, so older files have no such table.
    if version >= 6 {
        let bad_task_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM tasks
                 WHERE date(created_date) IS NOT created_date
                    OR (due IS NOT NULL AND date(due) IS NOT due)
                    OR (done_date IS NOT NULL AND date(done_date) IS NOT done_date)
                    OR (deleted_date IS NOT NULL AND date(deleted_date) IS NOT deleted_date)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_task_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // The task archive date arrived in schema version 9.
    if version >= 9 {
        let bad_archive_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM tasks
                 WHERE archived_date IS NOT NULL AND date(archived_date) IS NOT archived_date",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_archive_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // Goal trash arrived in a later schema version, so older files have no such column.
    if version >= db::goal_trash_version() {
        let bad_goal_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM goals
                 WHERE deleted_date IS NOT NULL AND date(deleted_date) IS NOT deleted_date",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_goal_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // Monsters arrived in schema version 13.
    if version >= 13 {
        let bad_monster_dates: i64 = conn
            .query_row(
                "SELECT
                   (SELECT COUNT(*) FROM monsters
                    WHERE date(created_date) IS NOT created_date
                       OR (retired_date IS NOT NULL AND date(retired_date) IS NOT retired_date))
                 + (SELECT COUNT(*) FROM monster_rounds
                    WHERE date(start_date) IS NOT start_date
                       OR (defeated_date IS NOT NULL AND date(defeated_date) IS NOT defeated_date))
                 + (SELECT COUNT(*) FROM monster_prizes
                    WHERE date(local_date) IS NOT local_date
                       OR (redeemed_date IS NOT NULL AND date(redeemed_date) IS NOT redeemed_date))",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_monster_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // XP and the weekly boss arrived in schema version 14.
    if version >= 14 {
        let bad_progress_dates: i64 = conn
            .query_row(
                "SELECT
                   (SELECT COUNT(*) FROM day_scores WHERE date(local_date) IS NOT local_date)
                 + (SELECT COUNT(*) FROM weekly_bosses
                    WHERE date(week_start) IS NOT week_start
                       OR (defeated_date IS NOT NULL AND date(defeated_date) IS NOT defeated_date))
                 + (SELECT COUNT(*) FROM boss_prizes
                    WHERE date(local_date) IS NOT local_date
                       OR (redeemed_date IS NOT NULL AND date(redeemed_date) IS NOT redeemed_date))",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_progress_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // The calendar arrived in schema version 15.
    if version >= 15 {
        let bad_event_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM events WHERE date(start_date) IS NOT start_date",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_event_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // Focus sessions arrived in schema version 18.
    if version >= 18 {
        let bad_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM focus_sessions WHERE date(local_date) IS NOT local_date",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // Event end dates and skipped days arrived in schema version 16.
    if version >= 16 {
        let bad_event_dates: i64 = conn
            .query_row(
                "SELECT (SELECT COUNT(*) FROM events
                         WHERE until_date IS NOT NULL AND date(until_date) IS NOT until_date)
                      + (SELECT COUNT(*) FROM event_skips WHERE date(skip_date) IS NOT skip_date)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_event_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // XP bonuses and daily quests arrived in schema version 19.
    if version >= 19 {
        let bad_progress_dates: i64 = conn
            .query_row(
                "SELECT (SELECT COUNT(*) FROM xp_bonuses WHERE date(local_date) IS NOT local_date)
                      + (SELECT COUNT(*) FROM daily_quests WHERE date(local_date) IS NOT local_date)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_progress_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // Holidays, day notes and weekly reviews arrived in schema versions 20, 21 and 22.
    if version >= 20 {
        let bad_holiday_dates: i64 = conn
            .query_row(
                "SELECT (SELECT COUNT(*) FROM holidays
                         WHERE date(start_date) IS NOT start_date OR date(end_date) IS NOT end_date)
                      + (SELECT COUNT(*) FROM holiday_skips WHERE date(local_date) IS NOT local_date)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_holiday_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    if version >= 21 {
        let bad_note_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM day_notes WHERE date(local_date) IS NOT local_date",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_note_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    if version >= 22 {
        let bad_review_dates: i64 = conn
            .query_row(
                "SELECT (SELECT COUNT(*) FROM week_reviews
                         WHERE date(week_start) IS NOT week_start
                            OR (finished_date IS NOT NULL AND date(finished_date) IS NOT finished_date)
                            OR (dismissed_date IS NOT NULL AND date(dismissed_date) IS NOT dismissed_date))
                      + (SELECT COUNT(*) FROM week_focus WHERE date(week_start) IS NOT week_start)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_review_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    // The shop arrived in schema version 25.
    if version >= 25 {
        let bad_shop_dates: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM shop_purchases
                 WHERE date(local_date) IS NOT local_date
                    OR (redeemed_date IS NOT NULL AND date(redeemed_date) IS NOT redeemed_date)",
                [],
                |r| r.get(0),
            )
            .map_err(|_| bad("the file is damaged"))?;
        if bad_shop_dates > 0 {
            return Err(bad("it contains invalid dates"));
        }
    }
    Ok(())
}

fn remove_if_exists(path: &str) -> Result<(), CoreError> {
    match fs::remove_file(path) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(io_error(e)),
        _ => Ok(()),
    }
}

fn remove_with_sidecars(path: &str) -> Result<(), CoreError> {
    remove_if_exists(path)?;
    remove_if_exists(&format!("{path}-wal"))?;
    remove_if_exists(&format!("{path}-shm"))
}

/// Replaces the live database with a backup. On any failure before the swap, the
/// current data is untouched. Returns the connection to use from now on.
pub fn restore(
    conn: Connection,
    db_path: &str,
    backup_path: &str,
) -> (Connection, Result<(), CoreError>) {
    restore_with(conn, db_path, backup_path, || Ok(()))
}

/// Same as `restore`, plus `after_swap`, which runs once the new file is in place.
/// If it fails, the old data comes back from the safety copy. Tests use it to force
/// that failure, because nothing else fails there in practice.
pub fn restore_with(
    conn: Connection,
    db_path: &str,
    backup_path: &str,
    after_swap: impl FnOnce() -> Result<(), CoreError>,
) -> (Connection, Result<(), CoreError>) {
    let staged = format!("{db_path}.restore");
    let safety = format!("{db_path}.before-restore");

    let prepared = (|| -> Result<(), CoreError> {
        remove_with_sidecars(&staged)?;
        fs::copy(backup_path, &staged).map_err(io_error)?;
        validate(&staged)?;
        // Bring older backups up to date on the private copy.
        let mut copy = Connection::open(&staged)?;
        db::migrate(&mut copy)?;
        rewards::ensure_meta(&copy)?;
        copy.close().map_err(|(_, e)| CoreError::from(e))?;
        export(&conn, &safety)
    })();
    if let Err(e) = prepared {
        let _ = remove_with_sidecars(&staged);
        return (conn, Err(e));
    }

    // Closing checkpoints the write-ahead log, so the old file stands alone.
    if let Err((conn, e)) = conn.close() {
        let _ = remove_with_sidecars(&staged);
        return (conn, Err(e.into()));
    }
    let swapped = (|| -> Result<Connection, CoreError> {
        remove_if_exists(&format!("{db_path}-wal"))?;
        remove_if_exists(&format!("{db_path}-shm"))?;
        fs::rename(&staged, db_path).map_err(io_error)?;
        after_swap()?;
        db::open(db_path)
    })();
    match swapped {
        Ok(conn) => (conn, Ok(())),
        Err(e) => {
            // Put the safety copy back so the app keeps working with its old data.
            let _ = remove_if_exists(&format!("{db_path}-wal"));
            let _ = remove_if_exists(&format!("{db_path}-shm"));
            let _ = fs::copy(&safety, db_path);
            let conn = db::open(db_path)
                .or_else(|_| Connection::open_in_memory().map_err(CoreError::from));
            match conn {
                Ok(conn) => (conn, Err(e)),
                Err(_) => panic!("couldn't reopen any database after a failed restore"),
            }
        }
    }
}

/// A quick count of what a database holds. Used to compare a backup with the current data.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupPreview {
    pub goals: u32,
    pub tasks: u32,
    pub entries: u32,
    pub treats: u32,
    /// Date of the newest log, as YYYY-MM-DD.
    pub newest_entry: Option<String>,
    pub schema_version: u32,
}

fn count(conn: &Connection, sql: &str) -> Result<u32, CoreError> {
    Ok(conn.query_row(sql, [], |r| r.get(0))?)
}

/// Counts the rows of an open database. Tasks only exist from schema version 6.
pub fn summarize(conn: &Connection) -> Result<BackupPreview, CoreError> {
    let version: i32 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    let tasks = if version >= 6 {
        count(
            conn,
            "SELECT COUNT(*) FROM tasks WHERE deleted_date IS NULL",
        )?
    } else {
        0
    };
    Ok(BackupPreview {
        goals: count(conn, "SELECT COUNT(*) FROM goals")?,
        tasks,
        entries: count(conn, "SELECT COUNT(*) FROM entries")?,
        treats: count(conn, "SELECT COUNT(*) FROM treats")?,
        newest_entry: conn.query_row("SELECT MAX(local_date) FROM entries", [], |r| r.get(0))?,
        schema_version: version as u32,
    })
}

/// Validates a backup on a private copy and reports what is inside. The original
/// file and the live data are never touched.
pub fn preview(db_path: &str, backup_path: &str) -> Result<BackupPreview, CoreError> {
    let copy = format!("{db_path}.preview");
    remove_with_sidecars(&copy)?;
    let result = (|| {
        fs::copy(backup_path, &copy).map_err(io_error)?;
        validate(&copy)?;
        let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let conn = Connection::open_with_flags(&copy, flags)?;
        conn.execute_batch("PRAGMA trusted_schema = OFF;")?;
        summarize(&conn)
    })();
    let _ = remove_with_sidecars(&copy);
    result
}

/// Preferences that travel with backups (theme and accent). Only these keys are
/// allowed, so the screens can't write arbitrary rows into `meta`.
const PREF_KEYS: [&str; 2] = ["theme", "accent"];

fn pref_key(key: &str) -> Result<String, CoreError> {
    if PREF_KEYS.contains(&key) {
        Ok(format!("pref_{key}"))
    } else {
        Err(CoreError::Invalid {
            reason: "unknown setting".into(),
        })
    }
}

pub fn get_pref(conn: &Connection, key: &str) -> Result<Option<String>, CoreError> {
    let key = pref_key(key)?;
    match conn.query_row("SELECT value FROM meta WHERE key = ?1", params![key], |r| {
        r.get(0)
    }) {
        Ok(value) => Ok(Some(value)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn set_pref(conn: &Connection, key: &str, value: &str) -> Result<(), CoreError> {
    let key = pref_key(key)?;
    if value.len() > 32 {
        return Err(CoreError::Invalid {
            reason: "that setting value is too long".into(),
        });
    }
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}
