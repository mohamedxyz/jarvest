//! Insights: a look back at a week or a month, and suggested time blocks for today.
//! Everything here only reads data. It never changes scores, XP, streaks or rewards.

use chrono::{Datelike, Days, Months, NaiveDate};
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::calendar;
use crate::dates::parse_date;
use crate::model::*;

/// First and last day of the week (Monday start) or month, `back` periods before today's.
fn range(period: Period, back: u32, today: NaiveDate) -> Result<(NaiveDate, NaiveDate), CoreError> {
    match period {
        Period::Week => {
            let monday = today - Days::new(u64::from(today.weekday().num_days_from_monday()));
            let start = monday - Days::new(7 * u64::from(back));
            Ok((start, start + Days::new(6)))
        }
        Period::Month => {
            let first = today.with_day(1).unwrap_or(today);
            let start = first
                .checked_sub_months(Months::new(back))
                .ok_or(CoreError::Invalid {
                    reason: "That month is too far back".into(),
                })?;
            let end = start + Months::new(1) - Days::new(1);
            Ok((start, end))
        }
        Period::Day => Err(CoreError::Invalid {
            reason: "Insights cover a week or a month".into(),
        }),
    }
}

fn sum(conn: &Connection, sql: &str, from: &str, to: &str) -> Result<u32, CoreError> {
    Ok(conn.query_row(sql, params![from, to], |r| r.get(0))?)
}

pub fn insights(
    conn: &Connection,
    period: Period,
    back: u32,
    today: &str,
) -> Result<Insights, CoreError> {
    let today_date = parse_date(today)?;
    let (start, end) = range(period, back, today_date)?;
    let (from, to) = (start.to_string(), end.to_string());
    let points = sum(
        conn,
        "SELECT COALESCE(SUM(earned), 0) FROM day_scores WHERE local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    let possible = sum(
        conn,
        "SELECT COALESCE(SUM(possible), 0) FROM day_scores WHERE local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    let active_days = sum(
        conn,
        "SELECT COUNT(*) FROM day_scores WHERE earned > 0 AND local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    let titan_days = sum(
        conn,
        "SELECT COUNT(*) FROM day_scores WHERE level = 'titan' AND local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    let focus_minutes = sum(
        conn,
        "SELECT COALESCE(SUM(minutes), 0) FROM focus_sessions WHERE local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    let bonus_xp = sum(
        conn,
        "SELECT COALESCE(SUM(points), 0) FROM xp_bonuses WHERE local_date BETWEEN ?1 AND ?2",
        &from,
        &to,
    )?;
    // The best day is the one with the most points. Ties go to the earlier day.
    let best: Option<(String, u32)> = conn
        .query_row(
            "SELECT local_date, earned FROM day_scores
             WHERE earned > 0 AND local_date BETWEEN ?1 AND ?2
             ORDER BY earned DESC, local_date LIMIT 1",
            params![from, to],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok();
    let (prev_start, prev_end) = range(period, back + 1, today_date)?;
    let previous_points = sum(
        conn,
        "SELECT COALESCE(SUM(earned), 0) FROM day_scores WHERE local_date BETWEEN ?1 AND ?2",
        &prev_start.to_string(),
        &prev_end.to_string(),
    )?;
    let name = if period == Period::Week {
        "week"
    } else {
        "month"
    };
    let mut text = format!(
        "My {name} {from} to {to}: {points} points over {active_days} active days, {titan_days} Titan days"
    );
    if focus_minutes > 0 {
        text.push_str(&format!(", {focus_minutes} minutes of focus"));
    }
    text.push('.');
    Ok(Insights {
        from,
        to,
        points,
        possible,
        active_days,
        titan_days,
        focus_minutes,
        bonus_xp,
        best_date: best.as_ref().map(|b| b.0.clone()),
        best_points: best.map_or(0, |b| b.1),
        previous_points,
        share_text: text,
    })
}

/// Plans for today that have no start time, each placed in the first free 30 minutes or more
/// between 09:00 and 20:00. Nothing is saved: the person decides whether to use a suggestion.
pub fn suggest_blocks(conn: &Connection, date: &str) -> Result<Vec<SuggestedBlock>, CoreError> {
    let blocks = calendar::day(conn, date)?;
    // Busy spans, sorted by start.
    let mut busy: Vec<(u32, u32)> = blocks
        .iter()
        .filter(|b| !b.done)
        .filter_map(|b| b.start_minutes.map(|s| (s, s + b.minutes)))
        .collect();
    busy.sort_unstable();
    let mut out = Vec::new();
    for b in blocks
        .iter()
        .filter(|b| b.start_minutes.is_none() && !b.done)
    {
        if !matches!(b.kind, CalendarKind::Goal | CalendarKind::Task) {
            continue;
        }
        let mut start = 9 * 60;
        for &(s, e) in &busy {
            if start + b.minutes <= s {
                break;
            }
            start = start.max(e);
        }
        if start + b.minutes > 20 * 60 {
            continue; // No room today.
        }
        busy.push((start, start + b.minutes));
        busy.sort_unstable();
        out.push(SuggestedBlock {
            kind: b.kind,
            id: b.id.clone(),
            title: b.title.clone(),
            start_minutes: start,
            minutes: b.minutes,
        });
    }
    Ok(out)
}
