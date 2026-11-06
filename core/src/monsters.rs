//! Custom monsters: a struggle the person names, fought by doing linked goals, habits and tasks.
//! See "V0.8.0 plan: custom monsters" in PLAN.md for the rules. Health is worked out from history
//! (entries and finished tasks), so only rounds are stored. `today` comes from the caller.

use std::collections::{BTreeMap, BTreeSet};

use chrono::{Days, NaiveDate};
use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::{clean_title, invalid, new_id};
use crate::model::*;
use crate::rewards;

const MIN_HEALTH: u32 = 10;
const MAX_HEALTH: u32 = 10_000;
/// A beaten monster rests this many days, then the next round starts.
const REST_DAYS: u64 = 3;
/// The final boss has this many times the health of the round it replaces.
const FINAL_BOSS_FACTOR: u32 = 5;
/// Treats won by beating a normal round and the final boss.
const PRIZES_NORMAL: u32 = 1;
const PRIZES_FINAL: u32 = 3;
/// Stops a runaway loop if the clock jumps a long way.
const MAX_STEPS: usize = 60;

struct Round {
    number: u32,
    start: NaiveDate,
    health: u32,
    final_boss: bool,
    defeated: Option<NaiveDate>,
}

struct Row {
    id: String,
    name: String,
    prize_treat_id: Option<String>,
    retired: Option<NaiveDate>,
}

/// A round after a normal one is a quarter stronger, rounded up.
fn grown(health: u32) -> u32 {
    (health * 5).div_ceil(4).min(MAX_HEALTH * 100)
}

fn date(text: &str) -> Result<NaiveDate, CoreError> {
    parse_date(text)
}

fn monster_rows(conn: &Connection) -> Result<Vec<Row>, CoreError> {
    let mut stmt =
        conn.prepare("SELECT id, name, prize_treat_id, retired_date FROM monsters ORDER BY rowid")?;
    let raw: Vec<(String, String, Option<String>, Option<String>)> = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
        .collect::<Result<_, _>>()?;
    raw.into_iter()
        .map(|(id, name, prize_treat_id, retired)| {
            Ok(Row {
                id,
                name,
                prize_treat_id,
                retired: retired.map(|d| date(&d)).transpose()?,
            })
        })
        .collect()
}

fn get_row(conn: &Connection, id: &str) -> Result<Row, CoreError> {
    monster_rows(conn)?
        .into_iter()
        .find(|m| m.id == id)
        .ok_or_else(|| invalid("That monster no longer exists"))
}

fn rounds_of(conn: &Connection, id: &str) -> Result<Vec<Round>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT round, start_date, health, final_boss, defeated_date
         FROM monster_rounds WHERE monster_id = ?1 ORDER BY round",
    )?;
    let raw: Vec<(u32, String, u32, bool, Option<String>)> = stmt
        .query_map([id], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
        })?
        .collect::<Result<_, _>>()?;
    raw.into_iter()
        .map(|(number, start, health, final_boss, defeated)| {
            Ok(Round {
                number,
                start: date(&start)?,
                health,
                final_boss,
                defeated: defeated.map(|d| date(&d)).transpose()?,
            })
        })
        .collect()
}

/// (kind, item id, power) for every link of a monster.
fn raw_links(conn: &Connection, id: &str) -> Result<Vec<(String, String, u32)>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT kind, item_id, power FROM monster_links WHERE monster_id = ?1 ORDER BY rowid",
    )?;
    let links = stmt
        .query_map([id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .collect::<Result<_, _>>()?;
    Ok(links)
}

/// Damage and healing for each day from `start` to `end`, both included.
fn day_effects(
    conn: &Connection,
    links: &[(String, String, u32)],
    start: NaiveDate,
    end: NaiveDate,
    today: NaiveDate,
) -> Result<BTreeMap<NaiveDate, (i64, i64)>, CoreError> {
    let mut effects: BTreeMap<NaiveDate, (i64, i64)> = BTreeMap::new();
    if end < start {
        return Ok(effects);
    }
    let (from, to) = (start.to_string(), end.to_string());
    for (kind, item, power) in links {
        let power = i64::from(*power);
        if kind == "goal" {
            let goal: Option<(String, bool)> = conn
                .query_row(
                    "SELECT created_date, avoid FROM goals WHERE id = ?1 AND deleted_date IS NULL",
                    [item],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            let Some((created, avoid)) = goal else {
                continue;
            };
            let mut stmt = conn.prepare(
                "SELECT DISTINCT local_date FROM entries
                 WHERE goal_id = ?1 AND local_date BETWEEN ?2 AND ?3",
            )?;
            let marked: BTreeSet<NaiveDate> = stmt
                .query_map(params![item, from, to], |r| r.get::<_, String>(0))?
                .map(|d| date(&d?))
                .collect::<Result<_, _>>()?;
            if avoid {
                // A logged day is a slip and heals the monster a little.
                for day in &marked {
                    effects.entry(*day).or_default().1 += power / 2;
                }
                // A day with nothing logged is clean once it is over, so today does not count yet.
                let first = start.max(date(&created)?);
                let last = end.min(today.checked_sub_days(Days::new(1)).unwrap_or(today));
                let mut day = first;
                while day <= last {
                    if !marked.contains(&day) {
                        effects.entry(day).or_default().0 += power;
                    }
                    day = day + Days::new(1);
                }
            } else {
                for day in &marked {
                    effects.entry(*day).or_default().0 += power;
                }
            }
        } else {
            // A repeating task is a chain of copies, so follow it to the newest copy.
            let mut id = item.clone();
            for _ in 0..400 {
                let row: Option<(Option<String>, Option<String>, Option<String>)> = conn
                    .query_row(
                        "SELECT done_date, deleted_date, next_id FROM tasks WHERE id = ?1",
                        [&id],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                    )
                    .optional()?;
                let Some((done, deleted, next)) = row else {
                    break;
                };
                if let (Some(done), None) = (done, deleted) {
                    let day = date(&done)?;
                    if day >= start && day <= end {
                        effects.entry(day).or_default().0 += power;
                    }
                }
                match next {
                    Some(next) => id = next,
                    None => break,
                }
            }
        }
    }
    Ok(effects)
}

/// Walks the days of a round. Returns the health at the end and the day it reached 0, if it did.
fn simulate(
    full: u32,
    effects: &BTreeMap<NaiveDate, (i64, i64)>,
    start: NaiveDate,
    end: NaiveDate,
) -> (u32, Option<NaiveDate>) {
    let full = i64::from(full);
    let mut health = full;
    let mut day = start;
    while day <= end {
        let (damage, heal) = effects.get(&day).copied().unwrap_or((0, 0));
        health = (health - damage + heal).min(full);
        if health <= 0 {
            return (0, Some(day));
        }
        day = day + Days::new(1);
    }
    (health as u32, None)
}

fn round_state(
    conn: &Connection,
    monster: &Row,
    round: &Round,
    today: NaiveDate,
) -> Result<(u32, Option<NaiveDate>), CoreError> {
    let links = raw_links(conn, &monster.id)?;
    let effects = day_effects(conn, &links, round.start, today, today)?;
    Ok(simulate(round.health, &effects, round.start, today))
}

pub fn create_monster(
    conn: &mut Connection,
    name: &str,
    health: u32,
    today: &str,
) -> Result<String, CoreError> {
    parse_date(today)?;
    let name = clean_title(name)?;
    if name.chars().count() > 60 {
        return Err(invalid("A monster's name can be at most 60 characters"));
    }
    if !(MIN_HEALTH..=MAX_HEALTH).contains(&health) {
        return Err(invalid(format!(
            "Health must be between {MIN_HEALTH} and {MAX_HEALTH}"
        )));
    }
    let id = new_id();
    let tx = conn.transaction()?;
    tx.execute(
        "INSERT INTO monsters (id, name, base_health, created_date) VALUES (?1, ?2, ?3, ?4)",
        params![id, name, health, today],
    )?;
    tx.execute(
        "INSERT INTO monster_rounds (monster_id, round, start_date, health) VALUES (?1, 1, ?2, ?3)",
        params![id, today, health],
    )?;
    tx.commit()?;
    Ok(id)
}

pub fn rename_monster(conn: &Connection, id: &str, name: &str) -> Result<(), CoreError> {
    let name = clean_title(name)?;
    if name.chars().count() > 60 {
        return Err(invalid("A monster's name can be at most 60 characters"));
    }
    let n = conn.execute(
        "UPDATE monsters SET name = ?2 WHERE id = ?1",
        params![id, name],
    )?;
    if n == 0 {
        return Err(invalid("That monster no longer exists"));
    }
    Ok(())
}

pub fn delete_monster(conn: &Connection, id: &str) -> Result<(), CoreError> {
    conn.execute("DELETE FROM monsters WHERE id = ?1", [id])?;
    Ok(())
}

/// Picks the treat that is the prize for beating this monster, or clears it.
pub fn set_prize_treat(
    conn: &Connection,
    id: &str,
    treat_id: Option<&str>,
) -> Result<(), CoreError> {
    if let Some(treat) = treat_id {
        let ok: bool = conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM treats WHERE id = ?1 AND removed = 0)",
            [treat],
            |r| r.get(0),
        )?;
        if !ok {
            return Err(invalid("That treat is not in the jar"));
        }
    }
    let n = conn.execute(
        "UPDATE monsters SET prize_treat_id = ?2 WHERE id = ?1",
        params![id, treat_id],
    )?;
    if n == 0 {
        return Err(invalid("That monster no longer exists"));
    }
    Ok(())
}

/// Links a goal, habit or task to a monster, or changes the power of an existing link.
pub fn link_item(
    conn: &Connection,
    id: &str,
    kind: MonsterLinkKind,
    item_id: &str,
    power: u32,
) -> Result<(), CoreError> {
    if ![5, 10, 20].contains(&power) {
        return Err(invalid(
            "Power must be light (5), medium (10) or heavy (20)",
        ));
    }
    let monster = get_row(conn, id)?;
    if monster.retired.is_some() {
        return Err(invalid("This monster is gone for good"));
    }
    let (table, text) = match kind {
        MonsterLinkKind::Goal => ("goals", "goal"),
        MonsterLinkKind::Task => ("tasks", "task"),
    };
    let exists: bool = conn.query_row(
        &format!("SELECT EXISTS (SELECT 1 FROM {table} WHERE id = ?1 AND deleted_date IS NULL)"),
        [item_id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(invalid(format!("That {text} no longer exists")));
    }
    conn.execute(
        "INSERT INTO monster_links (monster_id, kind, item_id, power) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT (monster_id, kind, item_id) DO UPDATE SET power = excluded.power",
        params![id, text, item_id, power],
    )?;
    Ok(())
}

pub fn unlink_item(
    conn: &Connection,
    id: &str,
    kind: MonsterLinkKind,
    item_id: &str,
) -> Result<(), CoreError> {
    let text = match kind {
        MonsterLinkKind::Goal => "goal",
        MonsterLinkKind::Task => "task",
    };
    conn.execute(
        "DELETE FROM monster_links WHERE monster_id = ?1 AND kind = ?2 AND item_id = ?3",
        params![id, text, item_id],
    )?;
    Ok(())
}

pub fn list_monsters(conn: &Connection, today: &str) -> Result<Vec<Monster>, CoreError> {
    let today_date = parse_date(today)?;
    let mut out = Vec::new();
    for monster in monster_rows(conn)? {
        let rounds = rounds_of(conn, &monster.id)?;
        let Some(last) = rounds.last() else { continue };
        let defeats = rounds.iter().filter(|r| r.defeated.is_some()).count() as u32;
        let (status, health, returns) = match (last.defeated, monster.retired) {
            (Some(_), Some(_)) => (MonsterStatus::Retired, 0, None),
            (Some(day), None) => (
                MonsterStatus::Resting,
                0,
                Some(day + Days::new(REST_DAYS + 1)),
            ),
            (None, _) => {
                let (health, defeated) = round_state(conn, &monster, last, today_date)?;
                // Beaten today but not yet recorded: `monster_tick` makes it final.
                (
                    MonsterStatus::Fighting,
                    if defeated.is_some() { 0 } else { health },
                    None,
                )
            }
        };
        let prize_treat = match &monster.prize_treat_id {
            Some(treat) => conn
                .query_row(
                    "SELECT title FROM treats WHERE id = ?1 AND removed = 0",
                    [treat],
                    |r| r.get(0),
                )
                .optional()?,
            None => None,
        };
        let mut links = Vec::new();
        for (kind, item, power) in raw_links(conn, &monster.id)? {
            let table = if kind == "goal" { "goals" } else { "tasks" };
            let title: Option<String> = conn
                .query_row(
                    &format!("SELECT title FROM {table} WHERE id = ?1"),
                    [&item],
                    |r| r.get(0),
                )
                .optional()?;
            links.push(MonsterLink {
                kind: if kind == "goal" {
                    MonsterLinkKind::Goal
                } else {
                    MonsterLinkKind::Task
                },
                item_id: item,
                title: title.unwrap_or_default(),
                power,
            });
        }
        out.push(Monster {
            id: monster.id.clone(),
            name: monster.name.clone(),
            status,
            round: last.number,
            max_health: last.health,
            health,
            final_boss: last.final_boss,
            returns_date: returns.map(|d| d.to_string()),
            retired_date: monster.retired.map(|d| d.to_string()),
            defeats,
            prize_treat,
            links,
        });
    }
    Ok(out)
}

/// Turns a monster into the final boss now: full health, 5 times the round it replaces, and it
/// never respawns. The round in progress (or the next one, while resting) is replaced.
pub fn final_boss(conn: &mut Connection, id: &str, today: &str) -> Result<(), CoreError> {
    let today_date = parse_date(today)?;
    let monster = get_row(conn, id)?;
    if monster.retired.is_some() {
        return Err(invalid("This monster is gone for good"));
    }
    let rounds = rounds_of(conn, id)?;
    let last = rounds
        .last()
        .ok_or_else(|| invalid("That monster has no rounds"))?;
    if last.final_boss {
        return Err(invalid("This monster is already the final boss"));
    }
    let tx = conn.transaction()?;
    if last.defeated.is_none() {
        tx.execute(
            "UPDATE monster_rounds SET final_boss = 1, health = ?3, start_date = ?4
             WHERE monster_id = ?1 AND round = ?2",
            params![
                id,
                last.number,
                last.health * FINAL_BOSS_FACTOR,
                today_date.to_string()
            ],
        )?;
    } else {
        // Resting: skip the rest and start the final round now.
        tx.execute(
            "INSERT INTO monster_rounds (monster_id, round, start_date, health, final_boss)
             VALUES (?1, ?2, ?3, ?4, 1)",
            params![
                id,
                last.number + 1,
                today_date.to_string(),
                grown(last.health) * FINAL_BOSS_FACTOR
            ],
        )?;
    }
    tx.commit()?;
    Ok(())
}

fn pick_treat(
    conn: &Connection,
    monster: &Row,
    round: u32,
    slot: u32,
) -> Result<Option<String>, CoreError> {
    let treats: Vec<String> = {
        let mut stmt = conn.prepare("SELECT id FROM treats WHERE removed = 0 ORDER BY seq")?;
        stmt.query_map([], |r| r.get(0))?
            .collect::<Result<_, _>>()?
    };
    if treats.is_empty() {
        return Ok(None);
    }
    // The treat the person attached is the first prize, if it is still in the jar.
    if slot == 0
        && let Some(attached) = &monster.prize_treat_id
        && treats.contains(attached)
    {
        return Ok(Some(attached.clone()));
    }
    // Otherwise a fixed roll, so a prize is the same whenever it is worked out.
    let mut hasher = Sha256::new();
    for part in [
        rewards::seed(conn)?,
        monster.id.clone(),
        format!("prize:{round}:{slot}"),
    ] {
        hasher.update(part.as_bytes());
        hasher.update([0]);
    }
    let digest = hasher.finalize();
    let value = u64::from_be_bytes(digest[..8].try_into().expect("8 bytes"));
    Ok(Some(treats[(value % treats.len() as u64) as usize].clone()))
}

/// Records beaten rounds and starts the next ones, up to `today`. Returns what was beaten, so the
/// app can celebrate once. Safe to call often.
pub fn tick(conn: &mut Connection, today: &str) -> Result<Vec<MonsterEvent>, CoreError> {
    let today_date = parse_date(today)?;
    let mut events = Vec::new();
    for monster in monster_rows(conn)? {
        if monster.retired.is_some() {
            continue;
        }
        for _ in 0..MAX_STEPS {
            let rounds = rounds_of(conn, &monster.id)?;
            let Some(last) = rounds.last() else { break };
            match last.defeated {
                None => {
                    let (_, defeated) = round_state(conn, &monster, last, today_date)?;
                    let Some(day) = defeated else { break };
                    let tx = conn.transaction()?;
                    tx.execute(
                        "UPDATE monster_rounds SET defeated_date = ?3 WHERE monster_id = ?1 AND round = ?2",
                        params![monster.id, last.number, day.to_string()],
                    )?;
                    let wanted = if last.final_boss {
                        PRIZES_FINAL
                    } else {
                        PRIZES_NORMAL
                    };
                    let mut won = 0;
                    if rewards::enabled(&tx)? {
                        for slot in 0..wanted {
                            if let Some(treat) = pick_treat(&tx, &monster, last.number, slot)? {
                                tx.execute(
                                    "INSERT INTO monster_prizes (id, monster_id, round, slot, treat_id, local_date)
                                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                                    params![new_id(), monster.id, last.number, slot, treat, day.to_string()],
                                )?;
                                won += 1;
                            }
                        }
                    }
                    // Beating a monster round is worth XP. It stays, like the win itself.
                    tx.execute(
                        "INSERT OR IGNORE INTO xp_bonuses (local_date, kind, points) VALUES (?1, ?2, ?3)",
                        params![
                            day.to_string(),
                            format!("monster:{}:{}", monster.id, last.number),
                            if last.final_boss { 60 } else { 15 }
                        ],
                    )?;
                    if last.final_boss {
                        // Gone for good: its links are freed for a new monster.
                        tx.execute(
                            "UPDATE monsters SET retired_date = ?2 WHERE id = ?1",
                            params![monster.id, day.to_string()],
                        )?;
                        tx.execute(
                            "DELETE FROM monster_links WHERE monster_id = ?1",
                            [&monster.id],
                        )?;
                    }
                    tx.commit()?;
                    events.push(MonsterEvent {
                        monster_id: monster.id.clone(),
                        name: monster.name.clone(),
                        round: last.number,
                        final_boss: last.final_boss,
                        prizes: won,
                    });
                    if last.final_boss {
                        break;
                    }
                }
                Some(day) => {
                    let next = day + Days::new(REST_DAYS + 1);
                    if next > today_date {
                        break;
                    }
                    conn.execute(
                        "INSERT INTO monster_rounds (monster_id, round, start_date, health)
                         VALUES (?1, ?2, ?3, ?4)",
                        params![
                            monster.id,
                            last.number + 1,
                            next.to_string(),
                            grown(last.health)
                        ],
                    )?;
                }
            }
        }
    }
    Ok(events)
}

/// Every round that was beaten, newest first. A final boss shows as "slain forever".
pub fn trophies(conn: &Connection) -> Result<Vec<MonsterTrophy>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT m.name, r.round, r.defeated_date, r.final_boss
         FROM monster_rounds r JOIN monsters m ON m.id = r.monster_id
         WHERE r.defeated_date IS NOT NULL
         ORDER BY r.defeated_date DESC, r.round DESC",
    )?;
    let list = stmt
        .query_map([], |r| {
            Ok(MonsterTrophy {
                monster_name: r.get(0)?,
                round: r.get(1)?,
                defeated_date: r.get(2)?,
                slain_forever: r.get(3)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(list)
}
