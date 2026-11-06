//! Task templates: save a task once, then make new copies of it in one tap.
//! A template never changes tasks that already exist, and tasks made from it are ordinary tasks.

use rusqlite::{Connection, OptionalExtension, params};

use crate::CoreError;
use crate::goals::{invalid, new_id};
use crate::model::*;
use crate::tasks;

const MAX_TEMPLATES: i64 = 50;

pub fn save(conn: &Connection, task_id: &str, name: &str, today: &str) -> Result<String, CoreError> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 60 {
        return Err(invalid("A template name needs 1 to 60 characters"));
    }
    let task = tasks::get_task(conn, task_id, today)?;
    let steps: Vec<String> = tasks::list_items(conn, task_id)?
        .into_iter()
        .map(|i| i.text)
        .collect();
    let taken: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM task_templates WHERE name = ?1 COLLATE NOCASE)",
        [name],
        |r| r.get(0),
    )?;
    if taken {
        return Err(invalid("A template with that name already exists"));
    }
    let count: i64 = conn.query_row("SELECT COUNT(*) FROM task_templates", [], |r| r.get(0))?;
    if count >= MAX_TEMPLATES {
        return Err(invalid("You can keep at most 50 templates"));
    }
    let id = new_id();
    conn.execute(
        "INSERT INTO task_templates (id, name, title, notes, priority, tag, minutes, steps)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![
            id,
            name,
            task.title,
            task.notes,
            task.priority.as_sql(),
            task.tag,
            task.duration,
            steps.join("\n")
        ],
    )?;
    Ok(id)
}

pub fn list(conn: &Connection) -> Result<Vec<TaskTemplate>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT id, name, title, steps, minutes FROM task_templates ORDER BY name COLLATE NOCASE",
    )?;
    let rows = stmt
        .query_map([], |r| {
            let steps: String = r.get(3)?;
            Ok(TaskTemplate {
                id: r.get(0)?,
                name: r.get(1)?,
                title: r.get(2)?,
                steps: steps.lines().filter(|l| !l.is_empty()).count() as u32,
                minutes: r.get(4)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(rows)
}

pub fn delete(conn: &Connection, id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM task_templates WHERE id = ?1", [id])?;
    Ok(())
}

/// Makes a new task from a template. `due` is optional. Returns the new task's id.
pub fn use_template(
    conn: &mut Connection,
    id: &str,
    due: Option<String>,
    today: &str,
) -> Result<String, CoreError> {
    let row = conn
        .query_row(
            "SELECT title, notes, priority, tag, minutes, steps FROM task_templates WHERE id = ?1",
            [id],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, Priority>(2)?,
                    r.get::<_, Option<String>>(3)?,
                    r.get::<_, Option<u32>>(4)?,
                    r.get::<_, String>(5)?,
                ))
            },
        )
        .optional()?;
    let Some((title, notes, priority, tag, minutes, steps)) = row else {
        return Err(invalid("That template no longer exists"));
    };
    let task_id = tasks::create_task(
        conn,
        NewTask {
            title,
            notes,
            due,
            priority,
            tag,
            repeat: None,
            items: steps.lines().filter(|l| !l.is_empty()).map(String::from).collect(),
            goal_id: None,
        },
        today,
    )?;
    if let Some(minutes) = minutes {
        // A length without a start time is allowed.
        conn.execute(
            "UPDATE tasks SET duration = ?2 WHERE id = ?1",
            params![task_id, minutes],
        )?;
    }
    Ok(task_id)
}
