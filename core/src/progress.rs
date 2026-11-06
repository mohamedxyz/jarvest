//! XP, hero levels and the weekly boss. Points come from the day score; `today` comes from the
//! caller, so nothing here reads the clock. The day score itself is never changed by this module.

use chrono::{Datelike, Days, NaiveDate};
use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::CoreError;
use crate::dates::parse_date;
use crate::goals::new_id;
use crate::model::*;
use crate::rewards;
use crate::score;

const MIN_BOSS_HEALTH: u32 = 100;
/// A boss has this many percent of the average of your last weeks.
const BOSS_PERCENT: u32 = 80;
const HISTORY_WEEKS: u64 = 4;

const BOSS_NAMES: [&str; 8] = [
    "the Procrastination Ogre",
    "the Couch Gremlin",
    "the Doomscroll Wraith",
    "the Snooze Dragon",
    "the Clutter Golem",
    "the Excuse Imp",
    "the Distraction Hydra",
    "the Lazy Troll",
];

/// XP needed to reach `level`. Level 2 needs 100, level 3 needs 250, level 4 needs 450, and so on.
fn threshold(level: u32) -> u32 {
    25 * (level - 1) * (level + 2)
}

fn title_for(level: u32) -> &'static str {
    match level {
        1..=2 => "Rookie",
        3..=4 => "Apprentice",
        5..=7 => "Ranger",
        8..=11 => "Knight",
        12..=16 => "Champion",
        17..=24 => "Hero",
        _ => "Legend",
    }
}

fn level_for(xp: u32) -> u32 {
    let mut level = 1;
    while threshold(level + 1) <= xp {
        level += 1;
    }
    level
}

fn week_start(day: NaiveDate) -> NaiveDate {
    day - Days::new(u64::from(day.weekday().num_days_from_monday()))
}

pub(crate) fn boss_name(week: &str) -> &'static str {
    let digest = Sha256::digest(week.as_bytes());
    BOSS_NAMES[usize::from(digest[0]) % BOSS_NAMES.len()]
}

fn points_between(conn: &Connection, from: NaiveDate, to: NaiveDate) -> Result<u32, CoreError> {
    Ok(conn.query_row(
        "SELECT COALESCE(SUM(earned), 0) FROM day_scores WHERE local_date BETWEEN ?1 AND ?2",
        params![from.to_string(), to.to_string()],
        |r| r.get(0),
    )?)
}

pub fn hero(conn: &Connection) -> Result<Hero, CoreError> {
    let xp: u32 = conn.query_row("SELECT COALESCE(SUM(earned), 0) FROM day_scores", [], |r| {
        r.get(0)
    })?;
    let level = level_for(xp);
    Ok(Hero {
        xp,
        level,
        title: title_for(level).to_string(),
        xp_into_level: xp - threshold(level),
        xp_for_next: threshold(level + 1) - threshold(level),
    })
}

/// Saves today's points. Today's row is replaced; a day with nothing planned or done has no row.
fn record_today(conn: &Connection, today: &str) -> Result<(), CoreError> {
    let s = score::day_score(conn, today)?;
    if s.possible == 0 && s.earned == 0 {
        conn.execute("DELETE FROM day_scores WHERE local_date = ?1", [today])?;
        return Ok(());
    }
    let level = match s.level {
        DayLevel::None => "none",
        DayLevel::Spark => "spark",
        DayLevel::Blaze => "blaze",
        DayLevel::Titan => "titan",
    };
    conn.execute(
        "INSERT INTO day_scores (local_date, earned, possible, level) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT (local_date) DO UPDATE SET
           earned = excluded.earned, possible = excluded.possible, level = excluded.level",
        params![today, s.earned, s.possible, level],
    )?;
    Ok(())
}

/// Health for a new boss: 80% of the average of the last four weeks, rounded up to 10, at least 100.
fn new_boss_health(conn: &Connection, week: NaiveDate) -> Result<u32, CoreError> {
    let mut total = 0u32;
    let mut weeks = 0u32;
    for back in 1..=HISTORY_WEEKS {
        let start = week - Days::new(7 * back);
        let points = points_between(conn, start, start + Days::new(6))?;
        if points > 0 {
            total += points;
            weeks += 1;
        }
    }
    let average = total.checked_div(weeks).unwrap_or(0);
    let health = (average * BOSS_PERCENT / 100).div_ceil(10) * 10;
    Ok(health.max(MIN_BOSS_HEALTH))
}

fn pick_treat(conn: &Connection, week: &str) -> Result<Option<String>, CoreError> {
    let treats: Vec<String> = {
        let mut stmt = conn.prepare("SELECT id FROM treats WHERE removed = 0 ORDER BY seq")?;
        stmt.query_map([], |r| r.get(0))?
            .collect::<Result<_, _>>()?
    };
    if treats.is_empty() {
        return Ok(None);
    }
    let mut hasher = Sha256::new();
    for part in [rewards::seed(conn)?, format!("boss:{week}")] {
        hasher.update(part.as_bytes());
        hasher.update([0]);
    }
    let digest = hasher.finalize();
    let value = u64::from_be_bytes(digest[..8].try_into().expect("8 bytes"));
    Ok(Some(treats[(value % treats.len() as u64) as usize].clone()))
}

/// The first day of the week on which the points reached `health`, if they did.
fn day_boss_fell(
    conn: &Connection,
    start: NaiveDate,
    health: u32,
) -> Result<Option<String>, CoreError> {
    let mut stmt = conn.prepare(
        "SELECT local_date, earned FROM day_scores WHERE local_date BETWEEN ?1 AND ?2
         ORDER BY local_date",
    )?;
    let rows = stmt.query_map(
        params![start.to_string(), (start + Days::new(6)).to_string()],
        |r| Ok((r.get::<_, String>(0)?, r.get::<_, u32>(1)?)),
    )?;
    let mut sum = 0;
    for row in rows {
        let (day, earned) = row?;
        sum += earned;
        if sum >= health {
            return Ok(Some(day));
        }
    }
    Ok(None)
}

/// Saves today's points, spawns this week's boss if it is missing, and records any boss that fell.
/// Safe to call often. Returns the bosses beaten by this call.
pub fn tick(conn: &mut Connection, today: &str) -> Result<Vec<BossEvent>, CoreError> {
    let today_date = parse_date(today)?;
    record_today(conn, today)?;
    let this_week = week_start(today_date);
    let exists: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM weekly_bosses WHERE week_start = ?1)",
        [this_week.to_string()],
        |r| r.get(0),
    )?;
    if !exists {
        let health = new_boss_health(conn, this_week)?;
        conn.execute(
            "INSERT INTO weekly_bosses (week_start, health) VALUES (?1, ?2)",
            params![this_week.to_string(), health],
        )?;
    }
    let open: Vec<(String, u32)> = {
        let mut stmt = conn
            .prepare("SELECT week_start, health FROM weekly_bosses WHERE defeated_date IS NULL")?;
        stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<Result<_, _>>()?
    };
    let mut events = Vec::new();
    for (week, health) in open {
        let start = parse_date(&week)?;
        let Some(day) = day_boss_fell(conn, start, health)? else {
            continue;
        };
        let tx = conn.transaction()?;
        tx.execute(
            "UPDATE weekly_bosses SET defeated_date = ?2 WHERE week_start = ?1",
            params![week, day],
        )?;
        let mut prizes = 0;
        if rewards::enabled(&tx)?
            && let Some(treat) = pick_treat(&tx, &week)?
        {
            tx.execute(
                "INSERT INTO boss_prizes (id, week_start, treat_id, local_date) VALUES (?1, ?2, ?3, ?4)",
                params![new_id(), week, treat, day],
            )?;
            prizes = 1;
        }
        tx.commit()?;
        events.push(BossEvent {
            name: boss_name(&week).to_string(),
            week_start: week,
            prizes,
        });
    }
    Ok(events)
}

fn record(
    conn: &Connection,
    week: String,
    health: u32,
    defeated: Option<String>,
) -> Result<BossRecord, CoreError> {
    let start = parse_date(&week)?;
    let points = points_between(conn, start, start + Days::new(6))?;
    Ok(BossRecord {
        name: boss_name(&week).to_string(),
        week_start: week,
        health,
        damage: points.min(health),
        defeated_date: defeated,
    })
}

/// This week's boss, or nothing if `progress_tick` has not run yet this week.
pub fn weekly_boss(conn: &Connection, today: &str) -> Result<Option<WeeklyBoss>, CoreError> {
    let today_date = parse_date(today)?;
    let week = week_start(today_date).to_string();
    let row: Option<(u32, Option<String>)> = conn
        .query_row(
            "SELECT health, defeated_date FROM weekly_bosses WHERE week_start = ?1",
            [&week],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((health, defeated)) = row else {
        return Ok(None);
    };
    let r = record(conn, week, health, defeated)?;
    Ok(Some(WeeklyBoss {
        name: r.name,
        week_start: r.week_start,
        health: r.health,
        damage: r.damage,
        defeated_date: r.defeated_date,
        days_left: 7 - today_date.weekday().num_days_from_monday(),
    }))
}

/// Every boss so far, newest first.
pub fn boss_history(conn: &Connection) -> Result<Vec<BossRecord>, CoreError> {
    let rows: Vec<(String, u32, Option<String>)> = {
        let mut stmt = conn.prepare(
            "SELECT week_start, health, defeated_date FROM weekly_bosses ORDER BY week_start DESC",
        )?;
        stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<Result<_, _>>()?
    };
    rows.into_iter()
        .map(|(week, health, defeated)| record(conn, week, health, defeated))
        .collect()
}
