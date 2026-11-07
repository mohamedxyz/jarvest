//! Search over tasks and goals for the search screen.

use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::parse_date;
use crate::model::*;
use crate::tasks;

/// Most results returned for each kind.
const LIMIT: i64 = 30;

/// Escapes `%`, `_` and `\` so the user's text is matched literally by SQL `LIKE`.
fn like_pattern(query: &str) -> String {
    let mut out = String::from("%");
    for c in query.chars() {
        if matches!(c, '%' | '_' | '\\') {
            out.push('\\');
        }
        out.push(c);
    }
    out.push('%');
    out
}

pub fn search(conn: &Connection, query: &str, today: &str) -> Result<SearchResults, CoreError> {
    parse_date(today)?;
    let query = query.trim();
    if query.is_empty() {
        return Ok(SearchResults {
            tasks: vec![],
            goals: vec![],
        });
    }
    let pattern = like_pattern(query);
    // Archived tasks are included (the screen labels them); trashed ones are not.
    let task_ids: Vec<String> = {
        // A trashed goal's milestone tasks are hidden here too, like everywhere else.
        let mut stmt = conn.prepare(&format!(
            "SELECT t.id FROM tasks t
             WHERE t.deleted_date IS NULL AND {}
               AND (t.title LIKE ?1 ESCAPE '\\' OR t.notes LIKE ?1 ESCAPE '\\'
                    OR t.tag LIKE ?1 ESCAPE '\\'
                    OR EXISTS (SELECT 1 FROM task_items i
                               WHERE i.task_id = t.id AND i.text LIKE ?1 ESCAPE '\\'))
             ORDER BY (t.done_date IS NOT NULL), (t.archived_date IS NOT NULL), t.rowid DESC
             LIMIT ?2",
            crate::tasks::VISIBLE
        ))?;
        let rows = stmt.query_map(params![pattern, LIMIT], |r| r.get(0))?;
        rows.collect::<Result<_, _>>()?
    };
    let found = task_ids
        .iter()
        .map(|id| tasks::get_task(conn, id, today))
        .collect::<Result<Vec<_>, _>>()?;
    let mut stmt = conn.prepare(
        "SELECT id, title, category, archived FROM goals
         WHERE title LIKE ?1 ESCAPE '\\' OR notes LIKE ?1 ESCAPE '\\' OR category LIKE ?1 ESCAPE '\\'
         ORDER BY archived, title COLLATE NOCASE LIMIT ?2",
    )?;
    let goals = stmt
        .query_map(params![pattern, LIMIT], |r| {
            Ok(GoalHit {
                id: r.get(0)?,
                title: r.get(1)?,
                category: r.get(2)?,
                archived: r.get::<_, i64>(3)? == 1,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(SearchResults {
        tasks: found,
        goals,
    })
}
