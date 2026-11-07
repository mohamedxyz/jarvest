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

/// The install's secret seed, which fixes every roll.
pub(crate) fn seed(conn: &Connection) -> Result<String, CoreError> {
    meta(conn, SEED_KEY)
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

/// Treats that can still be drawn, in jar order: not removed, and not a one-time treat
/// that was already won (from a target, a weekly boss or a monster). Undoing a win
/// deletes its row, so the treat comes back by itself.
pub(crate) const JAR_SQL: &str = "SELECT t.id FROM treats t WHERE t.removed = 0
    AND NOT (t.once = 1 AND (
        EXISTS (SELECT 1 FROM reward_draws d WHERE d.treat_id = t.id)
        OR EXISTS (SELECT 1 FROM monster_prizes m WHERE m.treat_id = t.id)
        OR EXISTS (SELECT 1 FROM boss_prizes b WHERE b.treat_id = t.id)
        OR EXISTS (SELECT 1 FROM shop_purchases p WHERE p.treat_id = t.id)))
    ORDER BY t.seq";

pub(crate) fn jar_treats(conn: &Connection) -> Result<Vec<String>, CoreError> {
    let mut stmt = conn.prepare(JAR_SQL)?;
    let ids = stmt
        .query_map([], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    Ok(ids)
}

pub fn progress(conn: &Connection) -> Result<RewardProgress, CoreError> {
    let misses = misses_since_draw(conn)?;
    let treats = jar_treats(conn)?.len() as u32;
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
    let treats = jar_treats(conn)?;

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
    add_treat_once(conn, title, false).map(|_| ())
}

/// Adds a treat. With `once`, it leaves the jar after it is won once.
pub fn add_treat_once(conn: &Connection, title: &str, once: bool) -> Result<String, CoreError> {
    let title = title.trim();
    if title.is_empty() {
        return Err(invalid("A treat needs a name"));
    }
    let id = uuid::Uuid::new_v4().to_string();
    conn.execute(
        "INSERT INTO treats (id, title, seq, once)
         VALUES (?1, ?2, (SELECT COALESCE(MAX(seq), 0) + 1 FROM treats), ?3)",
        params![id, title, once],
    )?;
    Ok(id)
}

/// Makes a treat one time only, or repeatable again. Past wins are kept either way.
pub fn set_treat_once(conn: &Connection, treat_id: &str, once: bool) -> Result<(), CoreError> {
    let n = conn.execute(
        "UPDATE treats SET once = ?2 WHERE id = ?1 AND removed = 0",
        params![treat_id, once],
    )?;
    if n == 0 {
        return Err(invalid("That treat is not in the jar"));
    }
    Ok(())
}

/// Won treats not used yet, one row per treat name, with how many are waiting.
/// `next_draw_id` is the oldest one, which "Use one" marks as enjoyed.
pub fn treat_stock(conn: &Connection) -> Result<Vec<StockItem>, CoreError> {
    let mut draws = list_draws(conn)?;
    draws.retain(|d| d.redeemed_date.is_none());
    // Oldest first, so the oldest win is used first.
    draws.sort_by(|a, b| a.local_date.cmp(&b.local_date));
    let mut stock: Vec<StockItem> = Vec::new();
    for draw in draws {
        match stock.iter_mut().find(|s| s.treat_title == draw.treat_title) {
            Some(item) => item.count += 1,
            None => stock.push(StockItem {
                treat_title: draw.treat_title,
                count: 1,
                next_draw_id: draw.id,
            }),
        }
    }
    stock.sort_by(|a, b| {
        a.treat_title
            .to_lowercase()
            .cmp(&b.treat_title.to_lowercase())
    });
    Ok(stock)
}

/// Takes a treat out of the jar. Past draws keep showing its name.
pub fn remove_treat(conn: &Connection, treat_id: &str) -> Result<(), CoreError> {
    conn.execute(
        "UPDATE treats SET removed = 1 WHERE id = ?1",
        params![treat_id],
    )?;
    Ok(())
}

/// The treats in the jar. A one-time treat that was already won is left out: it sits
/// in the stock until used.
pub fn list_treats(conn: &Connection) -> Result<Vec<Treat>, CoreError> {
    let in_jar = jar_treats(conn)?;
    let mut stmt =
        conn.prepare("SELECT id, title, once, price FROM treats WHERE removed = 0 ORDER BY seq")?;
    let treats = stmt
        .query_map([], |r| {
            Ok(Treat {
                id: r.get(0)?,
                title: r.get(1)?,
                once: r.get(2)?,
                price: r.get(3)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?
        .into_iter()
        .filter(|t| in_jar.contains(&t.id))
        .collect();
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
    let mut draws: Vec<RewardDraw> = stmt
        .query_map([], |r| {
            Ok(RewardDraw {
                id: r.get(0)?,
                treat_title: r.get(1)?,
                goal_title: r.get(2)?,
                local_date: r.get(3)?,
                comeback: r.get(4)?,
                seen: r.get(5)?,
                redeemed_date: r.get(6)?,
                from_monster: false,
                from_shop: false,
            })
        })?
        .collect::<Result<_, _>>()?;
    // Treats won by beating a monster. The same condition works because the columns share names.
    let mut stmt = conn.prepare(&format!(
        "SELECT d.id, t.title, m.name, d.local_date, d.seen, d.redeemed_date
         FROM monster_prizes d
         JOIN treats t ON t.id = d.treat_id
         JOIN monsters m ON m.id = d.monster_id
         WHERE {condition}
         ORDER BY d.local_date DESC, d.round DESC, d.slot"
    ))?;
    let prizes = stmt
        .query_map([], |r| {
            Ok(RewardDraw {
                id: r.get(0)?,
                treat_title: r.get(1)?,
                goal_title: r.get(2)?,
                local_date: r.get(3)?,
                comeback: false,
                seen: r.get(4)?,
                redeemed_date: r.get(5)?,
                from_monster: true,
                from_shop: false,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    draws.extend(prizes);
    // Treats won by beating the weekly boss.
    let mut stmt = conn.prepare(&format!(
        "SELECT d.id, t.title, d.week_start, d.local_date, d.seen, d.redeemed_date
         FROM boss_prizes d
         JOIN treats t ON t.id = d.treat_id
         WHERE {condition}
         ORDER BY d.local_date DESC"
    ))?;
    let boss_prizes = stmt
        .query_map([], |r| {
            let week: String = r.get(2)?;
            Ok(RewardDraw {
                id: r.get(0)?,
                treat_title: r.get(1)?,
                goal_title: format!("Weekly boss: {}", crate::progress::boss_name(&week)),
                local_date: r.get(3)?,
                comeback: false,
                seen: r.get(4)?,
                redeemed_date: r.get(5)?,
                from_monster: false,
                from_shop: false,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    draws.extend(boss_prizes);
    // Treats bought in the shop. They need no reveal, so they only show in the full list.
    if condition == "1" {
        let mut stmt = conn.prepare(
            "SELECT d.id, t.title, d.local_date, d.redeemed_date
             FROM shop_purchases d JOIN treats t ON t.id = d.treat_id
             ORDER BY d.local_date DESC, d.rowid DESC",
        )?;
        let bought = stmt
            .query_map([], |r| {
                Ok(RewardDraw {
                    id: r.get(0)?,
                    treat_title: r.get(1)?,
                    goal_title: "Shop".into(),
                    local_date: r.get(2)?,
                    comeback: false,
                    seen: true,
                    redeemed_date: r.get(3)?,
                    from_monster: false,
                    from_shop: true,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        draws.extend(bought);
    }
    // Newest first. The sort is stable, so each source keeps its own order within a day.
    draws.sort_by(|a, b| b.local_date.cmp(&a.local_date));
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
    conn.execute(
        "UPDATE monster_prizes SET seen = 1 WHERE id = ?1",
        params![draw_id],
    )?;
    conn.execute(
        "UPDATE boss_prizes SET seen = 1 WHERE id = ?1",
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
        return Ok(());
    }
    // Otherwise it may be a monster prize.
    let current: Option<Option<String>> = conn
        .query_row(
            "SELECT redeemed_date FROM monster_prizes WHERE id = ?1",
            params![draw_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(current) = current {
        let next = if current.is_some() { None } else { Some(today) };
        conn.execute(
            "UPDATE monster_prizes SET redeemed_date = ?2 WHERE id = ?1",
            params![draw_id, next],
        )?;
        return Ok(());
    }
    // Or a weekly boss prize.
    let current: Option<Option<String>> = conn
        .query_row(
            "SELECT redeemed_date FROM boss_prizes WHERE id = ?1",
            params![draw_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(current) = current {
        let next = if current.is_some() { None } else { Some(today) };
        conn.execute(
            "UPDATE boss_prizes SET redeemed_date = ?2 WHERE id = ?1",
            params![draw_id, next],
        )?;
        return Ok(());
    }
    // Or a treat bought in the shop.
    let current: Option<Option<String>> = conn
        .query_row(
            "SELECT redeemed_date FROM shop_purchases WHERE id = ?1",
            params![draw_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(current) = current {
        let next = if current.is_some() { None } else { Some(today) };
        conn.execute(
            "UPDATE shop_purchases SET redeemed_date = ?2 WHERE id = ?1",
            params![draw_id, next],
        )?;
    }
    Ok(())
}

// The save-up shop

/// XP needed for one coin.
pub const XP_PER_COIN: u32 = 10;

/// Coins earned (from XP), spent in the shop, and left. XP can go down when a day is
/// undone, so `balance` can briefly be below zero; nothing can be bought until it is
/// positive again, and nothing already bought is taken back.
pub fn coins(conn: &Connection) -> Result<Coins, CoreError> {
    let earned = crate::progress::hero(conn)?.xp / XP_PER_COIN;
    let spent: u32 = conn.query_row(
        "SELECT COALESCE(SUM(price), 0) FROM shop_purchases",
        [],
        |r| r.get(0),
    )?;
    Ok(Coins {
        earned,
        spent,
        balance: i64::from(earned) - i64::from(spent),
    })
}

/// Puts a treat in the shop for `price` coins, or takes it out of the shop with None.
/// It stays in the jar for random draws either way.
pub fn set_treat_price(
    conn: &Connection,
    treat_id: &str,
    price: Option<u32>,
) -> Result<(), CoreError> {
    if price.is_some_and(|p| !(1..=100_000).contains(&p)) {
        return Err(invalid("A price must be between 1 and 100,000 coins"));
    }
    let n = conn.execute(
        "UPDATE treats SET price = ?2 WHERE id = ?1 AND removed = 0",
        params![treat_id, price],
    )?;
    if n == 0 {
        return Err(invalid("That treat is not in the jar"));
    }
    Ok(())
}

/// Buys a treat from the shop. It goes into the stock. A one-time treat then leaves
/// the jar and the shop.
pub fn buy_treat(conn: &Connection, treat_id: &str, today: &str) -> Result<String, CoreError> {
    crate::dates::parse_date(today)?;
    let price: Option<u32> = conn
        .query_row(
            "SELECT price FROM treats WHERE id = ?1 AND removed = 0",
            params![treat_id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    let Some(price) = price else {
        return Err(invalid("That treat is not in the shop"));
    };
    if !jar_treats(conn)?.iter().any(|t| t == treat_id) {
        return Err(invalid("You already have this one-time treat"));
    }
    let balance = coins(conn)?.balance;
    if balance < i64::from(price) {
        return Err(invalid(format!(
            "Not enough coins yet: you have {}, it costs {price}",
            balance.max(0)
        )));
    }
    let id = uuid::Uuid::new_v4().to_string();
    conn.execute(
        "INSERT INTO shop_purchases (id, treat_id, price, local_date) VALUES (?1, ?2, ?3, ?4)",
        params![id, treat_id, price, today],
    )?;
    Ok(id)
}

/// Undoes a purchase that hasn't been used yet, giving the coins back.
pub fn refund_purchase(conn: &Connection, purchase_id: &str) -> Result<(), CoreError> {
    let n = conn.execute(
        "DELETE FROM shop_purchases WHERE id = ?1 AND redeemed_date IS NULL",
        params![purchase_id],
    )?;
    if n == 0 {
        return Err(invalid("Only a treat you haven't used yet can be refunded"));
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
