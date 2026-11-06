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
    // Day points plus bonuses (Titan days, quests, streaks, bosses, monsters).
    let xp: u32 = conn.query_row(
        "SELECT (SELECT COALESCE(SUM(earned), 0) FROM day_scores)
              + (SELECT COALESCE(SUM(points), 0) FROM xp_bonuses)",
        [],
        |r| r.get(0),
    )?;
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
        // Beating the boss is worth XP on the day it fell, and it stays.
        conn.execute(
            "INSERT OR IGNORE INTO xp_bonuses (local_date, kind, points) VALUES (?1, ?2, ?3)",
            params![day, format!("boss:{week}"), BOSS_BONUS],
        )?;
        events.push(BossEvent {
            name: boss_name(&week).to_string(),
            week_start: week,
            prizes,
        });
    }
    update_bonuses(conn, today)?;
    Ok(events)
}

const TITAN_BONUS: u32 = 10;
const QUEST_BONUS: u32 = 10;
const STREAK_BONUS: u32 = 20;
const BOSS_BONUS: u32 = 25;

fn set_bonus(conn: &Connection, date: &str, kind: &str, points: u32, on: bool) -> Result<(), CoreError> {
    if on {
        conn.execute(
            "INSERT OR IGNORE INTO xp_bonuses (local_date, kind, points) VALUES (?1, ?2, ?3)",
            params![date, kind, points],
        )?;
    } else {
        conn.execute(
            "DELETE FROM xp_bonuses WHERE local_date = ?1 AND kind = ?2",
            params![date, kind],
        )?;
    }
    Ok(())
}

/// The bonuses of the day in progress: a Titan day, the daily quest, and each full week of Blaze days.
/// They are given and taken back with the day, so undoing something never leaves XP behind.
fn update_bonuses(conn: &Connection, today: &str) -> Result<(), CoreError> {
    let score = score::day_score(conn, today)?;
    set_bonus(conn, today, "titan", TITAN_BONUS, score.level == DayLevel::Titan)?;
    ensure_quest(conn, today, &score)?;
    if let Some(quest) = daily_quest(conn, today)? {
        set_bonus(conn, today, "quest", QUEST_BONUS, quest.done)?;
    }
    let blaze_today = rank(score.level) >= 2;
    let streak = day_records(conn, today)?.blaze_streak;
    set_bonus(conn, today, "streak", STREAK_BONUS, blaze_today && streak > 0 && streak % 7 == 0)?;
    Ok(())
}

/// Makes today's quest the first time the day has something planned. It never changes afterwards.
fn ensure_quest(conn: &Connection, today: &str, score: &DayScore) -> Result<(), CoreError> {
    let exists: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM daily_quests WHERE local_date = ?1)",
        [today],
        |r| r.get(0),
    )?;
    if exists {
        return Ok(());
    }
    // Only goals that can be met today: 20 points, 2 tasks, or 2 goals.
    let mut options: Vec<(&str, u32)> = Vec::new();
    if score.possible >= 20 {
        let target = if score.possible >= 40 { 30 } else { 20 };
        options.push(("points", target));
    }
    if score.tasks_total >= 2 {
        options.push(("tasks", 2));
    }
    if score.goals_total >= 2 {
        options.push(("goals", 2));
    }
    if options.is_empty() {
        return Ok(());
    }
    let digest = Sha256::digest(format!("{}:{today}", rewards::seed(conn)?).as_bytes());
    let (kind, target) = options[usize::from(digest[0]) % options.len()];
    conn.execute(
        "INSERT INTO daily_quests (local_date, kind, target) VALUES (?1, ?2, ?3)",
        params![today, kind, target],
    )?;
    Ok(())
}

pub fn daily_quest(conn: &Connection, today: &str) -> Result<Option<DailyQuest>, CoreError> {
    parse_date(today)?;
    let row: Option<(String, u32)> = conn
        .query_row(
            "SELECT kind, target FROM daily_quests WHERE local_date = ?1",
            [today],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((kind, target)) = row else {
        return Ok(None);
    };
    let score = score::day_score(conn, today)?;
    let (text, progress) = match kind.as_str() {
        "points" => (format!("Earn {target} points today"), score.earned),
        "tasks" => (format!("Finish {target} tasks today"), score.tasks_done),
        _ => (format!("Complete {target} goals or habits today"), score.goals_done),
    };
    Ok(Some(DailyQuest {
        text,
        target,
        progress: progress.min(target),
        done: progress >= target,
        bonus: QUEST_BONUS,
    }))
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

fn level_from_sql(text: &str) -> DayLevel {
    match text {
        "spark" => DayLevel::Spark,
        "blaze" => DayLevel::Blaze,
        "titan" => DayLevel::Titan,
        _ => DayLevel::None,
    }
}

fn rank(level: DayLevel) -> u8 {
    match level {
        DayLevel::None => 0,
        DayLevel::Spark => 1,
        DayLevel::Blaze => 2,
        DayLevel::Titan => 3,
    }
}

/// Recorded days from `from` for `days` days (at most 62). Days without a row are left out.
pub fn day_rows(conn: &Connection, from: &str, days: u32) -> Result<Vec<DayRow>, CoreError> {
    let start = parse_date(from)?;
    let end = start + Days::new(u64::from(days.min(62).saturating_sub(1)));
    let mut stmt = conn.prepare(
        "SELECT local_date, earned, possible, level FROM day_scores
         WHERE local_date BETWEEN ?1 AND ?2 ORDER BY local_date",
    )?;
    let rows = stmt
        .query_map(params![start.to_string(), end.to_string()], |r| {
            Ok(DayRow {
                date: r.get(0)?,
                earned: r.get(1)?,
                possible: r.get(2)?,
                level: level_from_sql(&r.get::<_, String>(3)?),
            })
        })?
        .collect::<Result<_, _>>()?;
    Ok(rows)
}

pub fn day_detail(conn: &Connection, date: &str) -> Result<DayDetail, CoreError> {
    parse_date(date)?;
    let row = day_rows(conn, date, 1)?.into_iter().next();
    let mut stmt = conn.prepare(
        "SELECT title FROM tasks WHERE done_date = ?1 AND deleted_date IS NULL AND milestone_id IS NULL
         ORDER BY rowid",
    )?;
    let tasks_done = stmt
        .query_map([date], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    let mut stmt = conn.prepare(
        "SELECT DISTINCT g.title FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE e.local_date = ?1 AND g.deleted_date IS NULL ORDER BY g.title",
    )?;
    let goals_logged = stmt
        .query_map([date], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    Ok(DayDetail {
        date: date.to_string(),
        row,
        tasks_done,
        goals_logged,
    })
}

pub fn day_records(conn: &Connection, today: &str) -> Result<DayRecords, CoreError> {
    let today_date = parse_date(today)?;
    let mut stmt = conn.prepare("SELECT local_date, earned, level FROM day_scores ORDER BY local_date")?;
    let rows: Vec<(NaiveDate, u32, DayLevel)> = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, u32>(1)?,
                level_from_sql(&r.get::<_, String>(2)?),
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?
        .into_iter()
        .map(|(d, e, l)| Ok((parse_date(&d)?, e, l)))
        .collect::<Result<_, CoreError>>()?;

    let mut best: Option<(&NaiveDate, u32)> = None;
    let (mut longest, mut run, mut previous): (u32, u32, Option<NaiveDate>) = (0, 0, None);
    for (day, earned, level) in &rows {
        if best.is_none_or(|(_, points)| *earned > points) {
            best = Some((day, *earned));
        }
        if rank(*level) >= 2 {
            run = if previous.is_some_and(|p| p + Days::new(1) == *day) { run + 1 } else { 1 };
            longest = longest.max(run);
            previous = Some(*day);
        } else {
            run = 0;
            previous = None;
        }
    }
    // The current streak counts back from today, or from yesterday when today is still in progress.
    let blaze = |day: NaiveDate| rows.iter().any(|(d, _, l)| *d == day && rank(*l) >= 2);
    let mut cursor = if blaze(today_date) { today_date } else { today_date - Days::new(1) };
    let mut streak = 0;
    while blaze(cursor) {
        streak += 1;
        cursor = cursor - Days::new(1);
    }
    Ok(DayRecords {
        days_recorded: rows.len() as u32,
        titan_days: rows.iter().filter(|(_, _, l)| *l == DayLevel::Titan).count() as u32,
        best_day_date: best.map(|(d, _)| d.to_string()),
        best_day_points: best.map_or(0, |(_, p)| p),
        blaze_streak: streak,
        longest_blaze_streak: longest,
    })
}
