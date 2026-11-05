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
    /// The same count for the 12 eligible periods before those. Skipped periods are
    /// left out of both, so a holiday never reads as a decline.
    pub prev_met: u32,
    pub prev_eligible: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StatsOverview {
    pub goals: Vec<GoalStats>,
    pub met: u32,
    pub eligible: u32,
    pub prev_met: u32,
    pub prev_eligible: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MomentumStatus {
    /// Targets are being met more often than before.
    Advancing,
    Steady,
    /// Targets are being met less often than before.
    Slipping,
    /// Too little history to compare.
    NotEnoughData,
}

/// A plain verdict on whether the user is advancing or falling behind.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Momentum {
    pub status: MomentumStatus,
    /// Percentage points between the recent and the previous window.
    pub change_points: i32,
    pub rate_percent: u32,
    pub prev_rate_percent: u32,
    /// One sentence with the numbers behind the verdict.
    pub reason: String,
    /// What was compared, so "compared to what?" always has an answer.
    pub compared: String,
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
    /// Targets that rolled for a treat and won nothing this time.
    pub no_treat: u32,
    /// Treats won by this action. The app reveals them separately.
    pub drew: u32,
    /// Treats removed because this action undid a target that had won them.
    pub treats_removed: u32,
}

/// Where the user stands with the reward jar, for the meter on the Treats screen.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RewardProgress {
    pub enabled: bool,
    pub treats_in_jar: u32,
    /// Targets met since the last treat that won nothing.
    pub misses_since_draw: u32,
    /// The next treat is guaranteed within this many targets (1 means the next one).
    pub guaranteed_in: u32,
    pub base_percent: u32,
    pub comeback_percent: u32,
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
    /// Tasks finished on days inside the range (milestone tasks are left out: the
    /// milestone is what counts).
    pub tasks_done: u32,
    /// Tasks that were due inside the range, are past due, and are still not done.
    pub tasks_missed: u32,
    /// The first few of those, oldest due date first.
    pub missed_tasks: Vec<MissedTask>,
}

/// A task that was due in a report's range and was never finished.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MissedTask {
    pub id: String,
    pub title: String,
    pub due: String,
}

/// A goal in the trash. Restoring it brings back its history and rewards.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TrashedGoal {
    pub id: String,
    pub title: String,
    pub kind: GoalKind,
    pub category: Option<String>,
    pub deleted_date: String,
    /// Days before it is removed for good (0 when it is due to go).
    pub days_left: u32,
    /// How many check-ins it holds, so the screen can say what would be lost.
    pub entries: u32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Badge {
    pub id: String,
    pub title: String,
    pub description: String,
    pub earned: bool,
}

/// A goal's reminder time, in minutes after midnight.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Reminder {
    pub goal_id: String,
    pub title: String,
    pub minutes: u16,
}

// Tasks

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Priority {
    Low,
    Med,
    High,
}

impl Priority {
    pub fn as_sql(self) -> &'static str {
        match self {
            Priority::Low => "low",
            Priority::Med => "med",
            Priority::High => "high",
        }
    }
}

impl FromSql for Priority {
    fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
        match value.as_str()? {
            "low" => Ok(Priority::Low),
            "med" => Ok(Priority::Med),
            "high" => Ok(Priority::High),
            other => Err(FromSqlError::Other(
                format!("unknown priority {other}").into(),
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Repeat {
    Daily,
    Weekdays,
    Weekly,
    Monthly,
}

impl Repeat {
    pub fn as_sql(self) -> &'static str {
        match self {
            Repeat::Daily => "daily",
            Repeat::Weekdays => "weekdays",
            Repeat::Weekly => "weekly",
            Repeat::Monthly => "monthly",
        }
    }
}

impl FromSql for Repeat {
    fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
        match value.as_str()? {
            "daily" => Ok(Repeat::Daily),
            "weekdays" => Ok(Repeat::Weekdays),
            "weekly" => Ok(Repeat::Weekly),
            "monthly" => Ok(Repeat::Monthly),
            other => Err(FromSqlError::Other(
                format!("unknown repeat {other}").into(),
            )),
        }
    }
}

/// Which tasks `list_tasks` returns. Done tasks always come last.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TaskFilter {
    /// Open and done tasks.
    All,
    /// Open tasks due today.
    Today,
    /// Open tasks due before today.
    Overdue,
    /// Finished tasks only.
    Done,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TaskSort {
    /// Soonest due date first. Tasks with no date come last.
    Due,
    /// High priority first, then soonest due.
    Priority,
    /// Most recently created first.
    Newest,
    /// A to Z by title.
    Alphabetical,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Task {
    pub id: String,
    pub title: String,
    pub notes: String,
    pub due: Option<String>,
    pub priority: Priority,
    pub tag: Option<String>,
    pub repeat: Option<Repeat>,
    pub created_date: String,
    pub done_date: Option<String>,
    pub done: bool,
    /// Not done, and due before the `today` that was passed in.
    pub overdue: bool,
    pub items_done: u32,
    pub items_total: u32,
    /// The goal this task belongs to. Only groups tasks: it never logs the goal.
    pub goal_id: Option<String>,
    pub goal_title: Option<String>,
    /// Set when this task mirrors a milestone. Such a task is ticked, renamed and
    /// removed together with its milestone, on the goal's page.
    pub milestone_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NewTask {
    pub title: String,
    pub notes: String,
    pub due: Option<String>,
    pub priority: Priority,
    pub tag: Option<String>,
    pub repeat: Option<Repeat>,
    /// Checklist lines, in order.
    pub items: Vec<String>,
    pub goal_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TaskEdit {
    pub title: String,
    pub notes: String,
    pub due: Option<String>,
    pub priority: Priority,
    pub tag: Option<String>,
    pub repeat: Option<Repeat>,
    pub goal_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TaskItem {
    pub id: String,
    pub task_id: String,
    pub text: String,
    pub done: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TaskToggleResult {
    pub done: bool,
    /// Set when finishing a repeating task created the next copy.
    pub next_due: Option<String>,
    /// What the milestone behind this task earned or lost. Empty for ordinary tasks.
    pub reward: LogResult,
}

/// What `parse_quick` found in a line such as `Essay tomorrow !high #uni`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ParsedTask {
    pub title: String,
    pub due: Option<String>,
    pub priority: Option<Priority>,
    pub tag: Option<String>,
}
