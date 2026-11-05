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

/// Checks a file without changing it. Comparing the full schema with the one this
/// app would create rejects triggers, views, and altered tables.
pub fn validate(path: &str) -> Result<(), CoreError> {
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
        return Err(bad("it was made by a newer version of the app"));
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
        db::open(db_path)
    })();
    match swapped {
        Ok(conn) => (conn, Ok(())),
        Err(e) => {
            // Put the safety copy back so the app keeps working with its old data.
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
