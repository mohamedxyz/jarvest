//! The personal reward jar. See "Rewards" in PLAN.md for the rules.

use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::CoreError;
use crate::goals::invalid;
use crate::model::*;

const ROLL_RANGE: u64 = 10_000;
const BASE_THRESHOLD: u64 = ROLL_RANGE / 5; // 1 in 5
const COMEBACK_THRESHOLD: u64 = BASE_THRESHOLD * 2; // 2 in 5
/// A draw is guaranteed on this roll, counted since the last draw.
const PITY_ROLLS: u32 = 10;

const SEED_KEY: &str = "install_seed";
const ENABLED_KEY: &str = "rewards_enabled";

/// Creates default settings. Safe to run on every open and after a restore.
pub fn ensure_meta(conn: &Connection) -> Result<(), CoreError> {
    let seed = uuid::Uuid::new_v4().to_string();
    conn.execute(
        "INSERT OR IGNORE INTO meta (key, value) VALUES (?1, ?2)",
        params![SEED_KEY, seed],
    )?;
    conn.execute(
        "INSERT OR IGNORE INTO meta (key, value) VALUES (?1, '1')",
        params![ENABLED_KEY],
    )?;
    Ok(())
}

fn meta(conn: &Connection, key: &str) -> Result<String, CoreError> {
    Ok(
        conn.query_row("SELECT value FROM meta WHERE key = ?1", params![key], |r| {
            r.get(0)
        })?,
    )
}

pub fn enabled(conn: &Connection) -> Result<bool, CoreError> {
    Ok(meta(conn, ENABLED_KEY)? == "1")
}

pub fn set_enabled(conn: &Connection, on: bool) -> Result<(), CoreError> {
    conn.execute(
        "UPDATE meta SET value = ?2 WHERE key = ?1",
        params![ENABLED_KEY, if on { "1" } else { "0" }],
    )?;
    Ok(())
}

/// Fixed per target, so undoing and logging again can't produce a different roll.
fn roll(seed: &str, goal_id: &str, target_key: &str) -> (u64, u64) {
    let mut hasher = Sha256::new();
    for part in [seed, goal_id, target_key] {
        hasher.update(part.as_bytes());
        hasher.update([0]);
    }
    let digest = hasher.finalize();
    let first = u64::from_le_bytes(digest[..8].try_into().expect("8 bytes"));
    let second = u64::from_le_bytes(digest[8..16].try_into().expect("8 bytes"));
    (first % ROLL_RANGE, second)
}

/// What happened when a target was completed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    RewardsOff,
    /// There was nothing in the jar to win.
    JarEmpty,
    Draw,
    /// It rolled and won nothing this time.
    NoTreat,
}

fn misses_since_draw(conn: &Connection) -> Result<u32, CoreError> {
    Ok(conn.query_row(
        // Completions of goals in the trash are ignored, both as misses and as draws,
        // so the counter matches what the Treats screen shows. Restoring brings them back.
        "SELECT COUNT(*) FROM target_completions c JOIN goals g ON g.id = c.goal_id
         WHERE g.deleted_date IS NULL AND c.outcome = 'none' AND c.seq > (
             SELECT COALESCE(MAX(c2.seq), 0) FROM target_completions c2
             JOIN goals g2 ON g2.id = c2.goal_id
             WHERE g2.deleted_date IS NULL AND c2.outcome = 'draw')",
        [],
        |r| r.get(0),
    )?)
}

pub fn progress(conn: &Connection) -> Result<RewardProgress, CoreError> {
    let misses = misses_since_draw(conn)?;
    let treats: u32 = conn.query_row("SELECT COUNT(*) FROM treats WHERE removed = 0", [], |r| {
        r.get(0)
    })?;
    Ok(RewardProgress {
        enabled: enabled(conn)?,
        treats_in_jar: treats,
        misses_since_draw: misses,
        guaranteed_in: PITY_ROLLS.saturating_sub(misses).max(1),
        base_percent: (BASE_THRESHOLD * 100 / ROLL_RANGE) as u32,
        comeback_percent: (COMEBACK_THRESHOLD * 100 / ROLL_RANGE) as u32,
    })
}

/// Records a completed target and maybe draws a treat.
pub fn complete_target(
    conn: &Connection,
    goal_id: &str,
    target_key: &str,
    comeback: bool,
    today: &str,
) -> Result<Outcome, CoreError> {
    let completion_id = uuid::Uuid::new_v4().to_string();
    let treats: Vec<String> = {
        let mut stmt = conn.prepare("SELECT id FROM treats WHERE removed = 0 ORDER BY seq")?;
        stmt.query_map([], |r| r.get(0))?
            .collect::<Result<_, _>>()?
    };

    let mut draw = None;
    let outcome = if !enabled(conn)? {
        "rewards_off"
    } else if treats.is_empty() {
        "jar_empty"
    } else {
        let (value, treat_roll) = roll(&meta(conn, SEED_KEY)?, goal_id, target_key);
        let threshold = if comeback {
            COMEBACK_THRESHOLD
        } else {
            BASE_THRESHOLD
        };
        let misses = misses_since_draw(conn)?;
        if value < threshold || misses + 1 >= PITY_ROLLS {
            draw = Some(treats[(treat_roll % treats.len() as u64) as usize].clone());
            "draw"
        } else {
            "none"
        }
    };

    conn.execute(
        "INSERT INTO target_completions (id, goal_id, target_key, local_date, outcome, seq)
         VALUES (?1, ?2, ?3, ?4, ?5, (SELECT COALESCE(MAX(seq), 0) + 1 FROM target_completions))",
        params![completion_id, goal_id, target_key, today, outcome],
    )?;
    if let Some(treat_id) = draw {
        conn.execute(
            "INSERT INTO reward_draws (id, completion_id, treat_id, local_date, comeback)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                uuid::Uuid::new_v4().to_string(),
                completion_id,
                treat_id,
                today,
                comeback
            ],
        )?;
    }
    Ok(match outcome {
        "rewards_off" => Outcome::RewardsOff,
        "jar_empty" => Outcome::JarEmpty,
        "draw" => Outcome::Draw,
        _ => Outcome::NoTreat,
    })
}

pub fn add_treat(conn: &Connection, title: &str) -> Result<(), CoreError> {
    let title = title.trim();
    if title.is_empty() {
        return Err(invalid("A treat needs a name"));
    }
    conn.execute(
        "INSERT INTO treats (id, title, seq)
         VALUES (?1, ?2, (SELECT COALESCE(MAX(seq), 0) + 1 FROM treats))",
        params![uuid::Uuid::new_v4().to_string(), title],
    )?;
    Ok(())
}

/// Takes a treat out of the jar. Past draws keep showing its name.
pub fn remove_treat(conn: &Connection, treat_id: &str) -> Result<(), CoreError> {
    conn.execute(
        "UPDATE treats SET removed = 1 WHERE id = ?1",
        params![treat_id],
    )?;
    Ok(())
}

pub fn list_treats(conn: &Connection) -> Result<Vec<Treat>, CoreError> {
    let mut stmt = conn.prepare("SELECT id, title FROM treats WHERE removed = 0 ORDER BY seq")?;
    let treats = stmt
        .query_map([], |r| {
            Ok(Treat {
                id: r.get(0)?,
                title: r.get(1)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(treats)
}

fn draws_where(conn: &Connection, condition: &str) -> Result<Vec<RewardDraw>, CoreError> {
    let mut stmt = conn.prepare(&format!(
        "SELECT d.id, t.title, g.title, d.local_date, d.comeback, d.seen, d.redeemed_date
         FROM reward_draws d
         JOIN treats t ON t.id = d.treat_id
         JOIN target_completions c ON c.id = d.completion_id
         JOIN goals g ON g.id = c.goal_id
         WHERE g.deleted_date IS NULL AND {condition}
         ORDER BY c.seq DESC"
    ))?;
    let draws = stmt
        .query_map([], |r| {
            Ok(RewardDraw {
                id: r.get(0)?,
                treat_title: r.get(1)?,
                goal_title: r.get(2)?,
                local_date: r.get(3)?,
                comeback: r.get(4)?,
                seen: r.get(5)?,
                redeemed_date: r.get(6)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(draws)
}

pub fn list_draws(conn: &Connection) -> Result<Vec<RewardDraw>, CoreError> {
    draws_where(conn, "1")
}

pub fn unseen_draws(conn: &Connection) -> Result<Vec<RewardDraw>, CoreError> {
    draws_where(conn, "d.seen = 0")
}

pub fn mark_draw_seen(conn: &Connection, draw_id: &str) -> Result<(), CoreError> {
    conn.execute(
        "UPDATE reward_draws SET seen = 1 WHERE id = ?1",
        params![draw_id],
    )?;
    Ok(())
}

/// Marks a treat as enjoyed, or clears the mark if it was set by mistake.
pub fn toggle_redeemed(conn: &Connection, draw_id: &str, today: &str) -> Result<(), CoreError> {
    let current: Option<Option<String>> = conn
        .query_row(
            "SELECT redeemed_date FROM reward_draws WHERE id = ?1",
            params![draw_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(current) = current {
        let next = if current.is_some() { None } else { Some(today) };
        conn.execute(
            "UPDATE reward_draws SET redeemed_date = ?2 WHERE id = ?1",
            params![draw_id, next],
        )?;
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn test_threshold(comeback: bool) -> u64 {
    if comeback {
        COMEBACK_THRESHOLD
    } else {
        BASE_THRESHOLD
    }
}

#[cfg(test)]
pub(crate) fn test_roll(seed: &str, goal_id: &str, key: &str) -> u64 {
    roll(seed, goal_id, key).0
}
