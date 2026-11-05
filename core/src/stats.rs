//! Streaks and completion rates. See "Streaks and completion rates" in PLAN.md.

use std::collections::{BTreeMap, BTreeSet};

use chrono::{Datelike, Days, NaiveDate};
use rusqlite::{Connection, params};

use crate::CoreError;
use crate::dates::{
    is_scheduled, next_start, parse_date, period_start_ws, previous_start, week_start,
};
use crate::goals::{EPSILON, excused_periods, get_goal, list_goals, period_totals};
use crate::model::*;

/// How many recent eligible periods a completion rate covers.
const RATE_WINDOW: usize = 12;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct PeriodSummary {
    pub current_streak: u32,
    pub best_streak: u32,
    pub met: u32,
    pub eligible: u32,
    /// The same counts for the window just before the last `RATE_WINDOW` periods.
    pub prev_met: u32,
    pub prev_eligible: u32,
}

/// One run of consecutive met periods, as period start dates plus the count.
struct Run {
    first: NaiveDate,
    last: NaiveDate,
    length: u32,
}

#[allow(clippy::too_many_arguments)]
fn walk(
    created: NaiveDate,
    period: Period,
    target: f64,
    totals: &BTreeMap<NaiveDate, f64>,
    excused: &BTreeSet<NaiveDate>,
    weekdays: Option<u8>,
    today: NaiveDate,
    week: u8,
    strict: bool,
) -> (PeriodSummary, Vec<Run>) {
    let first = period_start_ws(created, period, week);
    let current = period_start_ws(today, period, week);
    let mut summary = PeriodSummary::default();
    let mut runs = Vec::new();
    let mut run: Option<Run> = None;
    let mut eligible_results = Vec::new();

    let mut start = first;
    while start <= current {
        let met = totals.get(&start).is_some_and(|t| t + EPSILON >= target);
        // A period with a skipped day is excused when unmet: it keeps the streak and
        // is left out of the rate. A daily habit's days off are never missed either.
        if !met && (excused.contains(&start) || !is_scheduled(weekdays, start)) {
            start = next_start(start, period);
            continue;
        }
        // The creation period and the unfinished current period only count if met.
        // A quit habit (`strict`) has no grace: a slip today or on day one counts at once.
        let grace = !strict && (start == first || start == current);
        if met {
            let r = run.get_or_insert(Run {
                first: start,
                last: start,
                length: 0,
            });
            r.last = start;
            r.length += 1;
            summary.best_streak = summary.best_streak.max(r.length);
        } else if !grace {
            runs.extend(run.take());
        }
        if met || !grace {
            eligible_results.push(met);
        }
        start = next_start(start, period);
    }
    summary.current_streak = run.as_ref().map_or(0, |r| r.length);
    runs.extend(run);

    let window = &eligible_results[eligible_results.len().saturating_sub(RATE_WINDOW)..];
    summary.eligible = window.len() as u32;
    summary.met = window.iter().filter(|met| **met).count() as u32;
    let before = eligible_results.len().saturating_sub(RATE_WINDOW);
    let prev = &eligible_results[before.saturating_sub(RATE_WINDOW)..before];
    summary.prev_eligible = prev.len() as u32;
    summary.prev_met = prev.iter().filter(|met| **met).count() as u32;
    (summary, runs)
}

/// Turns a quit habit's slip counts into clean-day counts: every day from `created`
/// to `today` becomes 1.0 when nothing was logged and 0.0 when a slip was. After this
/// the normal streak rules apply, with a target of one clean day.
pub fn clean_days(
    slips: &BTreeMap<NaiveDate, f64>,
    created: NaiveDate,
    today: NaiveDate,
) -> BTreeMap<NaiveDate, f64> {
    let mut clean = BTreeMap::new();
    let mut day = created;
    while day <= today {
        let slipped = slips.get(&day).is_some_and(|n| *n > 0.0);
        clean.insert(day, if slipped { 0.0 } else { 1.0 });
        day = next_start(day, Period::Day);
    }
    clean
}

/// The totals a goal's streak is worked out from: logged amounts, or clean days for a
/// quit habit.
fn streak_totals(
    conn: &Connection,
    goal: &Goal,
    period: Period,
    today: NaiveDate,
) -> Result<BTreeMap<NaiveDate, f64>, CoreError> {
    let totals = period_totals(conn, &goal.id, period)?;
    if goal.avoid {
        return Ok(clean_days(&totals, parse_date(&goal.created_date)?, today));
    }
    Ok(totals)
}

#[cfg(test)]
pub fn period_summary(
    created: NaiveDate,
    period: Period,
    target: f64,
    totals: &BTreeMap<NaiveDate, f64>,
    excused: &BTreeSet<NaiveDate>,
    weekdays: Option<u8>,
    today: NaiveDate,
) -> PeriodSummary {
    period_summary_ws(
        created, period, target, totals, excused, weekdays, today, 1, false,
    )
}

/// Like `period_summary`, with weeks starting on `week` (Monday = 1 ... Sunday = 7).
#[allow(clippy::too_many_arguments)]
pub fn period_summary_ws(
    created: NaiveDate,
    period: Period,
    target: f64,
    totals: &BTreeMap<NaiveDate, f64>,
    excused: &BTreeSet<NaiveDate>,
    weekdays: Option<u8>,
    today: NaiveDate,
    week: u8,
    strict: bool,
) -> PeriodSummary {
    walk(
        created, period, target, totals, excused, weekdays, today, week, strict,
    )
    .0
}

/// Every run of met periods for a goal, oldest first, including the current one.
pub fn streak_runs(
    conn: &Connection,
    goal_id: &str,
    today: &str,
) -> Result<Vec<StreakRun>, CoreError> {
    let goal = get_goal(conn, goal_id)?;
    let (Some(period), Some(target)) = (goal.period, goal.period_target) else {
        return Ok(Vec::new());
    };
    let today = parse_date(today)?;
    let totals = streak_totals(conn, &goal, period, today)?;
    let excused = excused_periods(conn, goal_id, period)?;
    let (_, runs) = walk(
        parse_date(&goal.created_date)?,
        period,
        target,
        &totals,
        &excused,
        goal.weekdays,
        today,
        week_start(conn),
        goal.avoid,
    );
    Ok(runs
        .into_iter()
        .map(|r| StreakRun {
            start: r.first.to_string(),
            end: (next_start(r.last, period) - Days::new(1)).to_string(),
            length: r.length,
        })
        .collect())
}

/// The last `count` periods of size `period`, oldest first, never before the goal began.
pub fn period_series(
    conn: &Connection,
    goal_id: &str,
    period: Period,
    count: u32,
    today: &str,
) -> Result<Vec<PeriodPoint>, CoreError> {
    let goal = get_goal(conn, goal_id)?;
    let week = week_start(conn);
    let first = period_start_ws(parse_date(&goal.created_date)?, period, week);
    let totals = period_totals(conn, goal_id, period)?;
    let excused = excused_periods(conn, goal_id, period)?;
    let target = if goal.period == Some(period) {
        goal.period_target
    } else {
        None
    };
    let mut start = period_start_ws(parse_date(today)?, period, week);
    let mut points = Vec::new();
    for _ in 0..count.min(366) {
        let total = totals.get(&start).copied().unwrap_or(0.0);
        let met = target.is_some_and(|t| total + EPSILON >= t);
        points.push(PeriodPoint {
            start: start.to_string(),
            total,
            target,
            met,
            excused: !met && excused.contains(&start),
        });
        if start <= first {
            break;
        }
        start = previous_start(start, period);
    }
    points.reverse();
    Ok(points)
}

/// Share of due days met, per weekday, across active daily goals.
pub fn weekday_stats(conn: &Connection, today: &str) -> Result<Vec<WeekdayStat>, CoreError> {
    let today = parse_date(today)?;
    let mut stats: Vec<WeekdayStat> = (0..7)
        .map(|weekday| WeekdayStat {
            weekday,
            met: 0,
            eligible: 0,
        })
        .collect();
    for goal in list_goals(conn, false)? {
        let (Some(Period::Day), Some(target)) = (goal.period, goal.period_target) else {
            continue;
        };
        let totals = streak_totals(conn, &goal, Period::Day, today)?;
        let excused = excused_periods(conn, &goal.id, Period::Day)?;
        let mut day = parse_date(&goal.created_date)?;
        while day <= today {
            let met = totals.get(&day).is_some_and(|t| t + EPSILON >= target);
            let due = is_scheduled(goal.weekdays, day) && !excused.contains(&day);
            // Today is still open, so it only counts once met.
            if met || (due && day < today) {
                let entry = &mut stats[day.weekday().num_days_from_monday() as usize];
                entry.eligible += 1;
                entry.met += u32::from(met);
            }
            day = next_start(day, Period::Day);
        }
    }
    Ok(stats)
}

/// How many missed tasks a report lists by name.
const MISSED_LIST_LIMIT: usize = 20;

/// Activity in the week or month that is `back` periods before the current one.
pub fn recap(
    conn: &Connection,
    period: Period,
    back: u32,
    today: &str,
) -> Result<Recap, CoreError> {
    if period == Period::Day {
        return Err(CoreError::Invalid {
            reason: "A recap covers a week or a month".into(),
        });
    }
    let mut start = period_start_ws(parse_date(today)?, period, week_start(conn));
    for _ in 0..back.min(240) {
        start = previous_start(start, period);
    }
    let end = next_start(start, period) - Days::new(1);
    let (from, to) = (start.to_string(), end.to_string());
    let (entries, active_days, goals_logged) = conn.query_row(
        "SELECT COUNT(*), COUNT(DISTINCT e.local_date), COUNT(DISTINCT e.goal_id)
         FROM entries e JOIN goals g ON g.id = e.goal_id
         WHERE g.archived = 0 AND g.deleted_date IS NULL AND e.local_date BETWEEN ?1 AND ?2",
        params![from, to],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    let targets_met = conn.query_row(
        "SELECT COUNT(*) FROM target_completions t JOIN goals g ON g.id = t.goal_id
         WHERE g.archived = 0 AND g.deleted_date IS NULL AND t.local_date BETWEEN ?1 AND ?2",
        params![from, to],
        |r| r.get(0),
    )?;
    // Tasks never count in goal stats, but the report lists them separately. Mirror
    // tasks of milestones are skipped, and so is anything in the trash.
    let tasks_done = conn.query_row(
        "SELECT COUNT(*) FROM tasks t
         WHERE t.deleted_date IS NULL AND t.milestone_id IS NULL
           AND t.done_date BETWEEN ?1 AND ?2",
        params![from, to],
        |r| r.get(0),
    )?;
    let mut stmt = conn.prepare(
        "SELECT t.id, t.title, t.due FROM tasks t
         WHERE t.deleted_date IS NULL AND t.milestone_id IS NULL AND t.done_date IS NULL
           AND t.due BETWEEN ?1 AND ?2 AND t.due < ?3
         ORDER BY t.due, t.rowid",
    )?;
    let all_missed: Vec<MissedTask> = stmt
        .query_map(params![from, to, today], |r| {
            Ok(MissedTask {
                id: r.get(0)?,
                title: r.get(1)?,
                due: r.get(2)?,
            })
        })?
        .collect::<Result<_, _>>()?;
    let tasks_missed = all_missed.len() as u32;
    let missed_tasks = all_missed.into_iter().take(MISSED_LIST_LIMIT).collect();
    Ok(Recap {
        start: from,
        end: to,
        entries,
        active_days,
        goals_logged,
        targets_met,
        tasks_done,
        tasks_missed,
        missed_tasks,
    })
}

fn stats_for(
    conn: &Connection,
    goal: &Goal,
    today: NaiveDate,
) -> Result<Option<GoalStats>, CoreError> {
    let (Some(period), Some(target)) = (goal.period, goal.period_target) else {
        return Ok(None);
    };
    let totals = streak_totals(conn, goal, period, today)?;
    let excused = excused_periods(conn, &goal.id, period)?;
    let summary = period_summary_ws(
        parse_date(&goal.created_date)?,
        period,
        target,
        &totals,
        &excused,
        goal.weekdays,
        today,
        week_start(conn),
        goal.avoid,
    );
    Ok(Some(GoalStats {
        goal_id: goal.id.clone(),
        title: goal.title.clone(),
        category: goal.category.clone(),
        period,
        current_streak: summary.current_streak,
        best_streak: summary.best_streak,
        met: summary.met,
        eligible: summary.eligible,
        prev_met: summary.prev_met,
        prev_eligible: summary.prev_eligible,
    }))
}

/// Stats for a single goal, or nothing if it has no repeating target.
pub fn goal_stats(
    conn: &Connection,
    goal_id: &str,
    today: &str,
) -> Result<Option<GoalStats>, CoreError> {
    stats_for(conn, &get_goal(conn, goal_id)?, parse_date(today)?)
}

/// Stats across all active goals with repeating targets.
pub fn overview(conn: &Connection, today: &str) -> Result<StatsOverview, CoreError> {
    let date = parse_date(today)?;
    let mut goals = Vec::new();
    for goal in list_goals(conn, false)? {
        if let Some(stats) = stats_for(conn, &goal, date)? {
            goals.push(stats);
        }
    }
    let met = goals.iter().map(|g| g.met).sum();
    let eligible = goals.iter().map(|g| g.eligible).sum();
    let prev_met = goals.iter().map(|g| g.prev_met).sum();
    let prev_eligible = goals.iter().map(|g| g.prev_eligible).sum();
    Ok(StatsOverview {
        goals,
        met,
        eligible,
        prev_met,
        prev_eligible,
    })
}

/// A goal's rate change in percentage points, or None without enough history.
fn goal_change(g: &GoalStats) -> Option<f64> {
    (g.eligible >= MIN_PERIODS && g.prev_eligible >= MIN_PERIODS).then(|| {
        f64::from(g.met) * 100.0 / f64::from(g.eligible)
            - f64::from(g.prev_met) * 100.0 / f64::from(g.prev_eligible)
    })
}

/// At least this many periods in each window before a verdict is given.
const MIN_PERIODS: u32 = 3;
/// A change of this many percentage points or more counts as advancing or slipping.
const CHANGE_POINTS: f64 = 5.0;

/// Whether the user is advancing or slipping: targets met in the recent window
/// compared with the window before it, across all active goals.
pub fn momentum(conn: &Connection, today: &str) -> Result<Momentum, CoreError> {
    let overview = overview(conn, today)?;
    let compared = "Your last 12 periods on each goal, compared with the 12 before. \
                    Skipped days are left out."
        .to_string();
    if overview.eligible < MIN_PERIODS || overview.prev_eligible < MIN_PERIODS {
        return Ok(Momentum {
            status: MomentumStatus::NotEnoughData,
            change_points: 0,
            rate_percent: 0,
            prev_rate_percent: 0,
            reason: "Not enough history yet. Keep logging and a verdict will appear.".into(),
            compared,
        });
    }
    let rate = f64::from(overview.met) * 100.0 / f64::from(overview.eligible);
    let prev = f64::from(overview.prev_met) * 100.0 / f64::from(overview.prev_eligible);
    let change = rate - prev;
    // Rounded on purpose, so the numbers on screen always add up.
    let (rate_percent, prev_rate_percent) = (rate.round() as u32, prev.round() as u32);
    let status = if change >= CHANGE_POINTS - 1e-9 {
        MomentumStatus::Advancing
    } else if change <= -CHANGE_POINTS + 1e-9 {
        MomentumStatus::Slipping
    } else {
        MomentumStatus::Steady
    };
    let mut reason = match status {
        MomentumStatus::Advancing => {
            format!("Targets met {rate_percent}%, up from {prev_rate_percent}%.")
        }
        MomentumStatus::Slipping => {
            format!("Targets met {rate_percent}%, down from {prev_rate_percent}%.")
        }
        _ => {
            format!("Targets met {rate_percent}%, about the same as before ({prev_rate_percent}%).")
        }
    };
    // Name the goal that moved the most in the same direction as the verdict.
    let mover = overview
        .goals
        .iter()
        .filter_map(|g| goal_change(g).map(|c| (g, c)))
        .filter(|(_, c)| match status {
            MomentumStatus::Slipping => *c <= -15.0,
            MomentumStatus::Advancing => *c >= 15.0,
            _ => false,
        })
        .min_by(|a, b| match status {
            MomentumStatus::Slipping => a.1.total_cmp(&b.1),
            _ => b.1.total_cmp(&a.1),
        });
    if let Some((goal, _)) = mover {
        let verb = if status == MomentumStatus::Slipping {
            "Biggest drop"
        } else {
            "Biggest gain"
        };
        reason.push_str(&format!(" {verb}: {}.", goal.title));
    }
    Ok(Momentum {
        status,
        change_points: change.round() as i32,
        rate_percent,
        prev_rate_percent,
        reason,
        compared,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        parse_date(s).unwrap()
    }

    fn summary(
        created: &str,
        period: Period,
        target: f64,
        met: &[(&str, f64)],
        today: &str,
    ) -> PeriodSummary {
        let totals = met.iter().map(|(s, v)| (d(s), *v)).collect();
        period_summary(
            d(created),
            period,
            target,
            &totals,
            &BTreeSet::new(),
            None,
            d(today),
        )
    }

    #[test]
    fn unfinished_current_period_does_not_break_a_streak() {
        let s = summary(
            "2026-11-01",
            Period::Day,
            1.0,
            &[
                ("2026-11-01", 1.0),
                ("2026-11-02", 1.0),
                ("2026-11-03", 1.0),
            ],
            "2026-11-04",
        );
        assert_eq!((s.current_streak, s.best_streak), (3, 3));
        assert_eq!((s.met, s.eligible), (3, 3));
    }

    #[test]
    fn current_period_counts_once_met() {
        let s = summary(
            "2026-11-01",
            Period::Day,
            1.0,
            &[("2026-11-01", 1.0), ("2026-11-02", 1.0)],
            "2026-11-02",
        );
        assert_eq!(s.current_streak, 2);
    }

    #[test]
    fn a_missed_period_resets_the_streak_but_keeps_the_best() {
        let s = summary(
            "2026-11-01",
            Period::Day,
            1.0,
            &[
                ("2026-11-01", 1.0),
                ("2026-11-02", 1.0),
                ("2026-11-04", 1.0),
            ],
            "2026-11-04",
        );
        assert_eq!((s.current_streak, s.best_streak), (1, 2));
        assert_eq!((s.met, s.eligible), (3, 4));
    }

    #[test]
    fn unmet_creation_period_is_not_penalized() {
        // Created on Sunday 2026-11-08, so that week never counted against the goal.
        let s = summary(
            "2026-11-08",
            Period::Week,
            3.0,
            &[("2026-11-09", 3.0)],
            "2026-11-16",
        );
        assert_eq!((s.current_streak, s.met, s.eligible), (1, 1, 1));
    }

    #[test]
    fn flexible_weekly_target_needs_only_the_target_count() {
        let s = summary(
            "2026-10-26",
            Period::Week,
            3.0,
            &[("2026-10-26", 3.0), ("2026-11-02", 2.0)],
            "2026-11-09",
        );
        assert_eq!((s.current_streak, s.best_streak), (0, 1));
        assert_eq!((s.met, s.eligible), (1, 2));
    }

    #[test]
    fn rate_covers_only_the_last_twelve_eligible_periods() {
        let dates: Vec<String> = (1..=20).map(|day| format!("2026-11-{day:02}")).collect();
        let met: Vec<(&str, f64)> = dates[..8].iter().map(|s| (s.as_str(), 1.0)).collect();
        let s = summary("2026-11-01", Period::Day, 1.0, &met, "2026-11-20");
        assert_eq!(s.eligible, 12);
        assert_eq!(s.met, 1); // Only November 8 falls inside the last 12 finished days.
    }

    #[test]
    fn date_before_creation_yields_empty_stats() {
        let s = summary("2026-11-10", Period::Day, 1.0, &[], "2026-11-01");
        assert_eq!(s, PeriodSummary::default());
    }

    #[test]
    fn month_periods_cross_the_leap_day_and_year_end() {
        let s = summary(
            "2023-12-15",
            Period::Month,
            1.0,
            &[
                ("2024-01-01", 1.0),
                ("2024-02-01", 1.0),
                ("2024-03-01", 1.0),
            ],
            "2024-03-31",
        );
        assert_eq!((s.current_streak, s.met, s.eligible), (3, 3, 3));
    }

    #[test]
    fn excused_unmet_period_keeps_the_streak_and_leaves_the_rate() {
        // Met 11-01, 11-02 and 11-04; 11-03 is missed; today is 11-05 (unfinished).
        let totals = [
            ("2026-11-01", 1.0),
            ("2026-11-02", 1.0),
            ("2026-11-04", 1.0),
        ]
        .iter()
        .map(|(s, v)| (d(s), *v))
        .collect();
        let run = |excused: &BTreeSet<NaiveDate>| {
            period_summary(
                d("2026-11-01"),
                Period::Day,
                1.0,
                &totals,
                excused,
                None,
                d("2026-11-05"),
            )
        };
        let excused = run(&BTreeSet::from([d("2026-11-03")]));
        assert_eq!(
            (excused.current_streak, excused.eligible, excused.met),
            (3, 3, 3)
        );
        let plain = run(&BTreeSet::new());
        assert_eq!((plain.current_streak, plain.eligible, plain.met), (1, 4, 3));
    }

    #[test]
    fn a_met_period_still_counts_when_it_has_a_skipped_day() {
        let totals = [("2026-11-03", 1.0)]
            .iter()
            .map(|(s, v)| (d(s), *v))
            .collect();
        let excused = BTreeSet::from([d("2026-11-03")]);
        let s = period_summary(
            d("2026-11-01"),
            Period::Day,
            1.0,
            &totals,
            &excused,
            None,
            d("2026-11-03"),
        );
        assert_eq!((s.met, s.current_streak), (1, 1));
    }
}
