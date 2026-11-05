//! CSV export of every log entry, for opening in a spreadsheet.

use std::fmt::Write as _;
use std::fs;

use rusqlite::Connection;

use crate::CoreError;

/// Quotes a field, and defuses spreadsheet formulas in text such as `=SUM(A1)`.
fn field(value: &str) -> String {
    let guarded = if value.starts_with(['=', '+', '-', '@', '\t', '\r']) {
        format!("'{value}")
    } else {
        value.to_string()
    };
    if guarded.contains([',', '"', '\n', '\r']) {
        format!("\"{}\"", guarded.replace('"', "\"\""))
    } else {
        guarded
    }
}

pub fn export_csv(conn: &Connection, dest: &str) -> Result<(), CoreError> {
    let mut out = String::from("date,goal,category,kind,amount,unit\n");
    let mut stmt = conn.prepare(
        "SELECT e.local_date, g.title, COALESCE(g.category, ''), g.kind, e.amount, COALESCE(g.unit, '')
         FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE g.deleted_date IS NULL
         ORDER BY e.local_date, e.seq",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, f64>(4)?,
            r.get::<_, String>(5)?,
        ))
    })?;
    for row in rows {
        let (date, title, category, kind, amount, unit) = row?;
        // Writing to a String can't fail.
        let _ = writeln!(
            out,
            "{date},{},{},{kind},{amount},{}",
            field(&title),
            field(&category),
            field(&unit)
        );
    }
    fs::write(dest, out).map_err(|e| CoreError::Database {
        detail: e.to_string(),
    })
}
