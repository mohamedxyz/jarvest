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

/// Tasks as CSV, with a header that `import_tasks_csv` reads back.
pub fn export_tasks_csv(conn: &Connection, dest: &str) -> Result<(), CoreError> {
    let mut out = String::from("title,due,priority,tag,notes,done\n");
    let mut stmt = conn.prepare(
        "SELECT title, COALESCE(due, ''), priority, COALESCE(tag, ''), notes, done_date IS NOT NULL
         FROM tasks WHERE deleted_date IS NULL AND milestone_id IS NULL
         ORDER BY created_date, rowid",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, String>(4)?,
            r.get::<_, bool>(5)?,
        ))
    })?;
    for row in rows {
        let (title, due, priority, tag, notes, done) = row?;
        let _ = writeln!(
            out,
            "{},{due},{priority},{},{},{done}",
            field(&title),
            field(&tag),
            field(&notes)
        );
    }
    fs::write(dest, out).map_err(|e| CoreError::Database {
        detail: e.to_string(),
    })
}

/// Escapes text for a JSON string.
fn json(value: &str) -> String {
    let mut out = String::from("\"");
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Everything readable (goals, logs, tasks, events, focus sessions) as one JSON file. It is for
/// reading and for other tools. A backup is still the way to move the app to another phone.
pub fn export_json(conn: &Connection, dest: &str) -> Result<(), CoreError> {
    let mut out = String::from("{\n");
    let mut list = |name: &str, sql: &str, cols: usize, last: bool| -> Result<(), CoreError> {
        let mut stmt = conn.prepare(sql)?;
        let rows: Vec<Vec<String>> = stmt
            .query_map([], |r| {
                (0..cols)
                    .map(|i| {
                        Ok(match r.get_ref(i)? {
                            rusqlite::types::ValueRef::Null => "null".to_string(),
                            rusqlite::types::ValueRef::Integer(n) => n.to_string(),
                            rusqlite::types::ValueRef::Real(f) => f.to_string(),
                            rusqlite::types::ValueRef::Text(t) => json(&String::from_utf8_lossy(t)),
                            rusqlite::types::ValueRef::Blob(_) => "null".to_string(),
                        })
                    })
                    .collect()
            })?
            .collect::<Result<_, _>>()?;
        let names: Vec<String> = stmt.column_names().iter().map(|c| json(c)).collect();
        let _ = write!(out, "  {}: [", json(name));
        for (i, row) in rows.iter().enumerate() {
            let fields: Vec<String> = row
                .iter()
                .zip(&names)
                .map(|(v, n)| format!("{n}: {v}"))
                .collect();
            let _ = write!(
                out,
                "{}\n    {{{}}}",
                if i > 0 { "," } else { "" },
                fields.join(", ")
            );
        }
        let _ = writeln!(
            out,
            "{}]{}",
            if rows.is_empty() { "" } else { "\n  " },
            if last { "" } else { "," }
        );
        Ok(())
    };
    list(
        "goals",
        "SELECT id, title, kind, category, unit, created_date FROM goals WHERE deleted_date IS NULL ORDER BY created_date, rowid",
        6,
        false,
    )?;
    list(
        "entries",
        "SELECT e.local_date AS date, g.title AS goal, e.amount FROM entries e JOIN goals g ON g.id = e.goal_id WHERE g.deleted_date IS NULL ORDER BY e.local_date, e.seq",
        3,
        false,
    )?;
    list(
        "tasks",
        "SELECT id, title, notes, due, priority, tag, done_date FROM tasks WHERE deleted_date IS NULL AND milestone_id IS NULL ORDER BY created_date, rowid",
        7,
        false,
    )?;
    list(
        "events",
        "SELECT id, title, start_date AS date, start_minutes, minutes, repeat FROM events ORDER BY start_date, start_minutes",
        6,
        false,
    )?;
    list(
        "focus_sessions",
        "SELECT title, local_date AS date, start_minutes, minutes FROM focus_sessions ORDER BY local_date, start_minutes",
        4,
        false,
    )?;
    list(
        "day_notes",
        "SELECT local_date AS date, body, mood FROM day_notes ORDER BY local_date",
        3,
        true,
    )?;
    out.push_str("}\n");
    fs::write(dest, out).map_err(|e| CoreError::Database {
        detail: e.to_string(),
    })
}

/// Splits CSV text into rows of fields. Quoted fields may hold commas, quotes and new lines.
pub(crate) fn parse_csv(text: &str) -> Vec<Vec<String>> {
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut cell = String::new();
    let mut quoted = false;
    let mut chars = text.trim_start_matches('\u{feff}').chars().peekable();
    while let Some(c) = chars.next() {
        match (quoted, c) {
            (true, '"') if chars.peek() == Some(&'"') => {
                cell.push('"');
                chars.next();
            }
            (true, '"') => quoted = false,
            (true, c) => cell.push(c),
            (false, '"') => quoted = true,
            (false, ',') => row.push(std::mem::take(&mut cell)),
            (false, '\r') => {}
            (false, '\n') => {
                row.push(std::mem::take(&mut cell));
                rows.push(std::mem::take(&mut row));
            }
            (false, c) => cell.push(c),
        }
    }
    if !cell.is_empty() || !row.is_empty() {
        row.push(cell);
        rows.push(row);
    }
    rows
}
