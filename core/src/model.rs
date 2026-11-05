//! Types shared with the Android app. Dates are `YYYY-MM-DD` strings.

use rusqlite::types::{FromSql, FromSqlError, FromSqlResult, ValueRef};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum GoalKind {
    YesNo,
    Habit,
    Numeric,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Period {
    Day,
    Week,
    Month,
}

impl GoalKind {
    pub fn as_sql(self) -> &'static str {
        match self {
            GoalKind::YesNo => "yes_no",
            GoalKind::Habit => "habit",
            GoalKind::Numeric => "numeric",
        }
    }
}

impl Period {
    pub fn as_sql(self) -> &'static str {
        match self {
            Period::Day => "day",
            Period::Week => "week",
            Period::Month => "month",
        }
    }
}

impl FromSql for GoalKind {
    fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
        match value.as_str()? {
            "yes_no" => Ok(GoalKind::YesNo),
            "habit" => Ok(GoalKind::Habit),
            "numeric" => Ok(GoalKind::Numeric),
            _ => Err(FromSqlError::InvalidType),
        }
    }
}

impl FromSql for Period {
    fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
        match value.as_str()? {
            "day" => Ok(Period::Day),
            "week" => Ok(Period::Week),
            "month" => Ok(Period::Month),
            _ => Err(FromSqlError::InvalidType),
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NewGoal {
    pub title: String,
    pub notes: String,
    pub kind: GoalKind,
    pub category: Option<String>,
    pub period: Option<Period>,
    pub period_target: Option<f64>,
    pub total_target: Option<f64>,
    pub unit: Option<String>,
    pub deadline: Option<String>,
    pub milestones: Vec<String>,
    /// Weekdays a daily habit is due on, as a bitmask (Monday = 1 ... Sunday = 64).
    /// `None` means every day.
    pub weekdays: Option<u8>,
}

/// The fields that can change without reinterpreting a goal's history.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GoalEdit {
    pub title: String,
    pub notes: String,
    pub category: Option<String>,
    pub unit: Option<String>,
    pub deadline: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Goal {
    pub id: String,
    pub title: String,
    pub notes: String,
    pub kind: GoalKind,
    pub category: Option<String>,
    pub period: Option<Period>,
    pub period_target: Option<f64>,
    pub total_target: Option<f64>,
    pub unit: Option<String>,
    pub deadline: Option<String>,
    pub created_date: String,
    pub archived: bool,
    pub total_logged: f64,
    /// True once a yes/no goal is ticked or a numeric total is reached.
    pub done: bool,
    /// Weekday bitmask for a daily habit (Monday = 1 ... Sunday = 64), or every day.
    pub weekdays: Option<u8>,
    pub milestones_done: u32,
    pub milestones_total: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Milestone {
    pub id: String,
    pub goal_id: String,
    pub title: String,
    pub done_date: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Entry {
    pub id: String,
    pub goal_id: String,
    pub local_date: String,
    pub amount: f64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TodayItem {
    pub goal_id: String,
    pub title: String,
    pub kind: GoalKind,
    pub category: Option<String>,
    pub unit: Option<String>,
    pub logged_today: bool,
    pub period: Option<Period>,
    pub period_progress: f64,
    pub period_target: Option<f64>,
    pub total_progress: f64,
    pub total_target: Option<f64>,
    /// Nothing more is needed from this goal for now.
    pub complete: bool,
    pub deadline: Option<String>,
    pub overdue: bool,
    pub current_streak: u32,
    pub next_milestone: Option<String>,
    pub milestones_done: u32,
    pub milestones_total: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct GoalStats {
    pub goal_id: String,
    pub title: String,
    pub category: Option<String>,
    pub period: Period,
    pub current_streak: u32,
    pub best_streak: u32,
    /// Periods met, out of `eligible`, within the last 12 eligible periods.
    pub met: u32,
    pub eligible: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StatsOverview {
    pub goals: Vec<GoalStats>,
    pub met: u32,
    pub eligible: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HeatmapDay {
    pub date: String,
    pub count: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Treat {
    pub id: String,
    pub title: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RewardDraw {
    pub id: String,
    pub treat_title: String,
    pub goal_title: String,
    pub local_date: String,
    pub comeback: bool,
    pub seen: bool,
    pub redeemed_date: Option<String>,
}

/// What a logging action caused. Won treats are read through `unseen_draws`, so
/// logs made in the app and in the widget share one reveal path.
#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct LogResult {
    /// A target was completed while the jar had no treats in it.
    pub jar_empty: bool,
}

/// One period of a goal's history, for charts. `start` is the period's first day.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PeriodPoint {
    pub start: String,
    pub total: f64,
    /// The goal's target for this period, if the goal repeats in this period size.
    pub target: Option<f64>,
    pub met: bool,
    /// Unmet, but skipped or paused, so it doesn't count against the goal.
    pub excused: bool,
}

/// How often daily goals were met on one weekday. `weekday` is 0 for Monday.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WeekdayStat {
    pub weekday: u8,
    pub met: u32,
    pub eligible: u32,
}

/// A run of consecutive met periods. Dates are the first and last day covered.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StreakRun {
    pub start: String,
    pub end: String,
    pub length: u32,
}

/// A week or month of activity across all active goals.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Recap {
    pub start: String,
    pub end: String,
    pub entries: u32,
    pub active_days: u32,
    pub goals_logged: u32,
    /// Targets completed on days inside the range.
    pub targets_met: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Badge {
    pub id: String,
    pub title: String,
    pub description: String,
    pub earned: bool,
}
