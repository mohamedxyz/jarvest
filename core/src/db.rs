//! Database connection setup and schema migrations.

use rusqlite::Connection;

use crate::CoreError;

/// Identifies a SQLite file as ours ("GOAL" in ASCII).
pub const APPLICATION_ID: i32 = 0x474F_414C;

/// One entry per schema version. Never edit a released migration: append one.
const MIGRATIONS: &[&str] = &[include_str!("sql/001_init.sql")];

pub const SCHEMA_VERSION: i32 = MIGRATIONS.len() as i32;

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
