//! Streaks and completion rates. See "Streaks and completion rates" in PLAN.md.

use std::collections::BTreeMap;

use chrono::NaiveDate;
use rusqlite::Connection;

use crate::CoreError;
use crate::dates::{next_start, parse_date, period_start};
use crate::goals::{EPSILON, get_goal, list_goals, period_totals};
use crate::model::*;

/// How many recent eligible periods a completion rate covers.
const RATE_WINDOW: usize = 12;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct PeriodSummary {
    pub current_streak: u32,
    pub best_streak: u32,
    pub met: u32,
    pub eligible: u32,
}

pub fn period_summary(
    created: NaiveDate,
    period: Period,
    target: f64,
    totals: &BTreeMap<NaiveDate, f64>,
    today: NaiveDate,
) -> PeriodSummary {
    let first = period_start(created, period);
    let current = period_start(today, period);
    let mut summary = PeriodSummary::default();
    let mut run = 0;
    let mut eligible_results = Vec::new();

    let mut start = first;
    while start <= current {
        let met = totals.get(&start).is_some_and(|t| t + EPSILON >= target);
        // The creation period and the unfinished current period only count if met.
        let grace = start == first || start == current;
        if met {
            run += 1;
            summary.best_streak = summary.best_streak.max(run);
        } else if !grace {
            run = 0;
        }
        if met || !grace {
            eligible_results.push(met);
        }
        start = next_start(start, period);
    }

    let window = &eligible_results[eligible_results.len().saturating_sub(RATE_WINDOW)..];
    summary.current_streak = run;
    summary.eligible = window.len() as u32;
    summary.met = window.iter().filter(|met| **met).count() as u32;
    summary
}

fn stats_for(
    conn: &Connection,
    goal: &Goal,
    today: NaiveDate,
) -> Result<Option<GoalStats>, CoreError> {
    let (Some(period), Some(target)) = (goal.period, goal.period_target) else {
        return Ok(None);
    };
    let totals = period_totals(conn, &goal.id, period)?;
    let summary = period_summary(
        parse_date(&goal.created_date)?,
        period,
        target,
        &totals,
        today,
    );
    Ok(Some(GoalStats {
        goal_id: goal.id.clone(),
        title: goal.title.clone(),
        period,
        current_streak: summary.current_streak,
        best_streak: summary.best_streak,
        met: summary.met,
        eligible: summary.eligible,
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
    Ok(StatsOverview {
        goals,
        met,
        eligible,
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
        period_summary(d(created), period, target, &totals, d(today))
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
}
