//! Rules, storage, stats, and rewards for the Goal Tracker app.
//!
//! `GoalCore` is the only type Android talks to. Every method takes the phone's
//! local date from the caller, and each change runs in a single transaction.

mod backup;
mod badges;
mod dates;
mod db;
mod export;
mod goals;
mod model;
mod quickadd;
mod reminders;
mod rewards;
mod search;
mod stats;
mod tasks;

use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::Connection;

pub use backup::BackupPreview;
pub use model::*;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("database error: {detail}")]
    Database { detail: String },
    #[error("{reason}")]
    Invalid { reason: String },
    #[error("invalid backup: {reason}")]
    InvalidBackup { reason: String },
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        CoreError::Database {
            detail: e.to_string(),
        }
    }
}

#[derive(uniffi::Object)]
pub struct GoalCore {
    conn: Mutex<Connection>,
    db_path: String,
}

impl GoalCore {
    fn conn(&self) -> MutexGuard<'_, Connection> {
        // A panic can't leave a half-applied change, because transactions roll back.
        self.conn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[uniffi::export]
impl GoalCore {
    #[uniffi::constructor]
    pub fn open(db_path: String) -> Result<Arc<Self>, CoreError> {
        let conn = db::open(&db_path)?;
        rewards::ensure_meta(&conn)?;
        Ok(Arc::new(Self {
            conn: Mutex::new(conn),
            db_path,
        }))
    }

    // Goals

    pub fn create_goal(&self, goal: NewGoal, today: String) -> Result<String, CoreError> {
        goals::create_goal(&mut self.conn(), goal, &today)
    }

    pub fn update_goal(&self, goal_id: String, edit: GoalEdit) -> Result<(), CoreError> {
        goals::update_goal(&self.conn(), &goal_id, edit)
    }

    pub fn set_archived(&self, goal_id: String, archived: bool) -> Result<(), CoreError> {
        goals::set_archived(&self.conn(), &goal_id, archived)
    }

    pub fn delete_goal(&self, goal_id: String) -> Result<(), CoreError> {
        goals::delete_goal(&self.conn(), &goal_id)
    }

    pub fn get_goal(&self, goal_id: String) -> Result<Goal, CoreError> {
        goals::get_goal(&self.conn(), &goal_id)
    }

    pub fn list_goals(&self, archived: bool) -> Result<Vec<Goal>, CoreError> {
        goals::list_goals(&self.conn(), archived)
    }

    pub fn categories(&self) -> Result<Vec<String>, CoreError> {
        goals::categories(&self.conn())
    }

    /// Creates a quit habit, met by not logging. Every entry on it is a slip.
    pub fn create_avoid_goal(
        &self,
        title: String,
        notes: String,
        category: Option<String>,
        today: String,
    ) -> Result<String, CoreError> {
        goals::create_avoid_goal(&mut self.conn(), &title, &notes, category, &today)
    }

    /// First day of the week: 1 = Monday, 6 = Saturday, 7 = Sunday.
    pub fn week_start(&self) -> u8 {
        dates::week_start(&self.conn())
    }

    pub fn set_week_start(&self, day: u8) -> Result<(), CoreError> {
        dates::set_week_start(&self.conn(), day)
    }

    /// Renames a folder (a goal category) on every goal that uses it.
    pub fn rename_category(&self, from: String, to: String) -> Result<(), CoreError> {
        goals::rename_category(&self.conn(), &from, &to)
    }

    /// The order the user arranged folders in, as names.
    pub fn folder_order(&self) -> Vec<String> {
        goals::folder_order(&self.conn())
    }

    pub fn set_folder_order(&self, names: Vec<String>) -> Result<(), CoreError> {
        goals::set_folder_order(&self.conn(), &names)
    }

    pub fn today(&self, today: String) -> Result<Vec<TodayItem>, CoreError> {
        goals::today_items(&self.conn(), &today)
    }

    // Tasks

    pub fn create_task(&self, task: NewTask, today: String) -> Result<String, CoreError> {
        tasks::create_task(&mut self.conn(), task, &today)
    }

    /// Creates a task from one line such as `Essay tomorrow !high #uni`.
    pub fn quick_add_task(&self, raw: String, today: String) -> Result<String, CoreError> {
        let parsed = quickadd::parse_quick(&raw, &today)?;
        let task = NewTask {
            title: parsed.title,
            notes: String::new(),
            due: parsed.due,
            priority: parsed.priority.unwrap_or(Priority::Med),
            tag: parsed.tag,
            repeat: None,
            items: vec![],
            goal_id: None,
        };
        tasks::create_task(&mut self.conn(), task, &today)
    }

    /// Shows what a quick-add line would become, without saving anything.
    pub fn parse_quick(&self, raw: String, today: String) -> Result<ParsedTask, CoreError> {
        quickadd::parse_quick(&raw, &today)
    }

    pub fn update_task(&self, task_id: String, edit: TaskEdit) -> Result<(), CoreError> {
        tasks::update_task(&self.conn(), &task_id, edit)
    }

    pub fn get_task(&self, task_id: String, today: String) -> Result<Task, CoreError> {
        tasks::get_task(&self.conn(), &task_id, &today)
    }

    pub fn toggle_task(
        &self,
        task_id: String,
        today: String,
    ) -> Result<TaskToggleResult, CoreError> {
        tasks::toggle_task(&mut self.conn(), &task_id, &today)
    }

    /// Moves a task to the trash. Use `restore_task` to undo.
    pub fn delete_task(&self, task_id: String, today: String) -> Result<(), CoreError> {
        tasks::delete_task(&self.conn(), &task_id, &today)
    }

    pub fn restore_task(&self, task_id: String) -> Result<(), CoreError> {
        tasks::restore_task(&self.conn(), &task_id)
    }

    pub fn list_tasks(
        &self,
        filter: TaskFilter,
        sort: TaskSort,
        today: String,
    ) -> Result<Vec<Task>, CoreError> {
        tasks::list_tasks(&self.conn(), filter, sort, &today)
    }

    /// Tasks linked to one goal, open first. They only group there: finishing one never logs the goal.
    pub fn list_goal_tasks(&self, goal_id: String, today: String) -> Result<Vec<Task>, CoreError> {
        tasks::list_goal_tasks(&self.conn(), &goal_id, &today)
    }

    pub fn list_trash(&self, today: String) -> Result<Vec<Task>, CoreError> {
        tasks::list_trash(&self.conn(), &today)
    }

    /// Permanently removes tasks that were deleted 30 or more days ago.
    pub fn purge_trash(&self, today: String) -> Result<u32, CoreError> {
        tasks::purge_trash(&self.conn(), &today)
    }

    /// Open tasks due today or earlier, for the Today screen.
    pub fn due_tasks(&self, today: String) -> Result<Vec<Task>, CoreError> {
        tasks::due_tasks(&self.conn(), &today)
    }

    pub fn list_task_items(&self, task_id: String) -> Result<Vec<TaskItem>, CoreError> {
        tasks::list_items(&self.conn(), &task_id)
    }

    pub fn add_task_item(&self, task_id: String, text: String) -> Result<String, CoreError> {
        tasks::add_item(&self.conn(), &task_id, &text)
    }

    pub fn toggle_task_item(&self, item_id: String) -> Result<(), CoreError> {
        tasks::toggle_item(&self.conn(), &item_id)
    }

    pub fn delete_task_item(&self, item_id: String) -> Result<(), CoreError> {
        tasks::delete_item(&self.conn(), &item_id)
    }

    // Task power: archive, bulk edit, tags, custom repeat, search

    /// Hides a task in the archive. Not allowed for a milestone's task.
    pub fn archive_task(&self, task_id: String, today: String) -> Result<(), CoreError> {
        tasks::archive_task(&self.conn(), &task_id, &today)
    }

    pub fn unarchive_task(&self, task_id: String) -> Result<(), CoreError> {
        tasks::unarchive_task(&self.conn(), &task_id)
    }

    pub fn list_archived_tasks(&self, today: String) -> Result<Vec<Task>, CoreError> {
        tasks::list_archived(&self.conn(), &today)
    }

    /// Moves finished archived tasks to the trash. Returns how many.
    pub fn trash_archived_done(&self, today: String) -> Result<u32, CoreError> {
        tasks::trash_archived_done(&self.conn(), &today)
    }

    /// Archives tasks finished `after_days` or more days ago. Call it on app open.
    pub fn auto_archive_tasks(&self, after_days: u32, today: String) -> Result<u32, CoreError> {
        tasks::auto_archive(&self.conn(), after_days, &today)
    }

    pub fn delete_task_forever(&self, task_id: String) -> Result<(), CoreError> {
        tasks::delete_task_forever(&self.conn(), &task_id)
    }

    pub fn empty_task_trash(&self) -> Result<u32, CoreError> {
        tasks::empty_trash(&self.conn())
    }

    /// Applies one change to many tasks (finish, archive, trash, due, priority, tag, goal).
    pub fn bulk_update_tasks(
        &self,
        ids: Vec<String>,
        edit: BulkEdit,
        today: String,
    ) -> Result<BulkResult, CoreError> {
        tasks::bulk_update(&mut self.conn(), &ids, edit, &today)
    }

    pub fn list_tags(&self) -> Result<Vec<TagCount>, CoreError> {
        tasks::list_tags(&self.conn())
    }

    /// Sets "every N" and the weekdays of a task's repeat. Choose the repeat itself with `update_task`.
    pub fn set_task_repeat_rule(
        &self,
        task_id: String,
        every: u32,
        days: Option<u8>,
    ) -> Result<(), CoreError> {
        tasks::set_repeat_rule(&self.conn(), &task_id, every, days)
    }

    /// Replaces the checklist with one step per line.
    pub fn replace_task_items(&self, task_id: String, lines: Vec<String>) -> Result<(), CoreError> {
        tasks::replace_items(&mut self.conn(), &task_id, &lines)
    }

    /// Looks through tasks (title, notes, tag, checklist) and goals. At most 30 of each.
    pub fn search(&self, query: String, today: String) -> Result<SearchResults, CoreError> {
        search::search(&self.conn(), &query, &today)
    }

    // Logging

    pub fn toggle_check_in(&self, goal_id: String, today: String) -> Result<LogResult, CoreError> {
        goals::toggle_check_in(&mut self.conn(), &goal_id, &today, &today)
    }

    /// Like `toggle_check_in`, for an earlier `day` that was missed.
    pub fn toggle_check_in_on(
        &self,
        goal_id: String,
        day: String,
        today: String,
    ) -> Result<LogResult, CoreError> {
        goals::toggle_check_in(&mut self.conn(), &goal_id, &day, &today)
    }

    pub fn log_amount(
        &self,
        goal_id: String,
        amount: f64,
        today: String,
    ) -> Result<LogResult, CoreError> {
        goals::log_amount(&mut self.conn(), &goal_id, amount, &today, &today)
    }

    /// Like `log_amount`, for an earlier `day` that was missed.
    pub fn log_amount_on(
        &self,
        goal_id: String,
        amount: f64,
        day: String,
        today: String,
    ) -> Result<LogResult, CoreError> {
        goals::log_amount(&mut self.conn(), &goal_id, amount, &day, &today)
    }

    pub fn delete_entry(&self, entry_id: String, today: String) -> Result<(), CoreError> {
        goals::delete_entry(&mut self.conn(), &entry_id, &today)
    }

    pub fn list_entries(&self, goal_id: String, limit: u32) -> Result<Vec<Entry>, CoreError> {
        goals::list_entries(&self.conn(), &goal_id, limit)
    }

    // Skipping and pausing

    /// Skips one day: an unmet period containing it keeps the streak.
    pub fn skip_day(&self, goal_id: String, day: String) -> Result<(), CoreError> {
        goals::pause_goal(&mut self.conn(), &goal_id, &day, &day)
    }

    /// Skips every day from `from` to `to`, such as for a holiday.
    pub fn pause_goal(&self, goal_id: String, from: String, to: String) -> Result<(), CoreError> {
        goals::pause_goal(&mut self.conn(), &goal_id, &from, &to)
    }

    pub fn unpause_goal(&self, goal_id: String, from: String, to: String) -> Result<(), CoreError> {
        goals::unpause_goal(&self.conn(), &goal_id, &from, &to)
    }

    pub fn skipped_days(&self, goal_id: String) -> Result<Vec<String>, CoreError> {
        goals::skipped_days(&self.conn(), &goal_id)
    }

    // Milestones

    pub fn list_milestones(&self, goal_id: String) -> Result<Vec<Milestone>, CoreError> {
        goals::list_milestones(&self.conn(), &goal_id)
    }

    pub fn add_milestone(
        &self,
        goal_id: String,
        title: String,
        today: String,
    ) -> Result<(), CoreError> {
        goals::add_milestone(&mut self.conn(), &goal_id, &title, &today)
    }

    pub fn toggle_milestone(
        &self,
        milestone_id: String,
        today: String,
    ) -> Result<LogResult, CoreError> {
        goals::toggle_milestone(&mut self.conn(), &milestone_id, &today)
    }

    pub fn delete_milestone(&self, milestone_id: String) -> Result<(), CoreError> {
        goals::delete_milestone(&mut self.conn(), &milestone_id)
    }

    pub fn move_milestone(&self, milestone_id: String, earlier: bool) -> Result<(), CoreError> {
        goals::move_milestone(&mut self.conn(), &milestone_id, earlier)
    }

    // Stats

    pub fn stats_overview(&self, today: String) -> Result<StatsOverview, CoreError> {
        stats::overview(&self.conn(), &today)
    }

    /// Whether the user is advancing or slipping, with the numbers behind it.
    pub fn momentum(&self, today: String) -> Result<Momentum, CoreError> {
        stats::momentum(&self.conn(), &today)
    }

    pub fn goal_stats(
        &self,
        goal_id: String,
        today: String,
    ) -> Result<Option<GoalStats>, CoreError> {
        stats::goal_stats(&self.conn(), &goal_id, &today)
    }

    pub fn heatmap(
        &self,
        goal_id: Option<String>,
        from: String,
        to: String,
    ) -> Result<Vec<HeatmapDay>, CoreError> {
        goals::heatmap(&self.conn(), goal_id.as_deref(), &from, &to)
    }

    // Rewards

    pub fn rewards_enabled(&self) -> Result<bool, CoreError> {
        rewards::enabled(&self.conn())
    }

    pub fn set_rewards_enabled(&self, enabled: bool) -> Result<(), CoreError> {
        rewards::set_enabled(&self.conn(), enabled)
    }

    /// How close the next treat is, for the meter on the Treats screen.
    pub fn reward_progress(&self) -> Result<RewardProgress, CoreError> {
        rewards::progress(&self.conn())
    }

    pub fn add_treat(&self, title: String) -> Result<(), CoreError> {
        rewards::add_treat(&self.conn(), &title)
    }

    pub fn remove_treat(&self, treat_id: String) -> Result<(), CoreError> {
        rewards::remove_treat(&self.conn(), &treat_id)
    }

    pub fn list_treats(&self) -> Result<Vec<Treat>, CoreError> {
        rewards::list_treats(&self.conn())
    }

    pub fn list_draws(&self) -> Result<Vec<RewardDraw>, CoreError> {
        rewards::list_draws(&self.conn())
    }

    pub fn unseen_draws(&self) -> Result<Vec<RewardDraw>, CoreError> {
        rewards::unseen_draws(&self.conn())
    }

    pub fn mark_draw_seen(&self, draw_id: String) -> Result<(), CoreError> {
        rewards::mark_draw_seen(&self.conn(), &draw_id)
    }

    pub fn toggle_redeemed(&self, draw_id: String, today: String) -> Result<(), CoreError> {
        rewards::toggle_redeemed(&self.conn(), &draw_id, &today)
    }

    // Ordering

    /// Moves a goal one place earlier or later among goals in its category.
    pub fn move_goal(&self, goal_id: String, earlier: bool) -> Result<(), CoreError> {
        goals::move_goal(&mut self.conn(), &goal_id, earlier)
    }

    // Insights

    pub fn period_series(
        &self,
        goal_id: String,
        period: Period,
        count: u32,
        today: String,
    ) -> Result<Vec<PeriodPoint>, CoreError> {
        stats::period_series(&self.conn(), &goal_id, period, count, &today)
    }

    pub fn streak_runs(&self, goal_id: String, today: String) -> Result<Vec<StreakRun>, CoreError> {
        stats::streak_runs(&self.conn(), &goal_id, &today)
    }

    pub fn weekday_stats(&self, today: String) -> Result<Vec<WeekdayStat>, CoreError> {
        stats::weekday_stats(&self.conn(), &today)
    }

    /// Activity in the week or month `back` periods before the current one.
    pub fn recap(&self, period: Period, back: u32, today: String) -> Result<Recap, CoreError> {
        stats::recap(&self.conn(), period, back, &today)
    }

    pub fn badges(&self, today: String) -> Result<Vec<Badge>, CoreError> {
        badges::badges(&self.conn(), &today)
    }

    // Reminders

    /// Sets a goal's reminder time in minutes after midnight, or clears it with `None`.
    pub fn set_goal_reminder(
        &self,
        goal_id: String,
        minutes: Option<u16>,
    ) -> Result<(), CoreError> {
        reminders::set_goal_reminder(&self.conn(), &goal_id, minutes)
    }

    pub fn goal_reminder(&self, goal_id: String) -> Result<Option<u16>, CoreError> {
        reminders::goal_reminder(&self.conn(), &goal_id)
    }

    pub fn list_reminders(&self) -> Result<Vec<Reminder>, CoreError> {
        reminders::list_reminders(&self.conn())
    }

    pub fn daily_reminder(&self) -> Result<Option<u16>, CoreError> {
        reminders::daily_reminder(&self.conn())
    }

    pub fn set_daily_reminder(&self, minutes: Option<u16>) -> Result<(), CoreError> {
        reminders::set_daily_reminder(&self.conn(), minutes)
    }

    /// What a reminder should say right now, or `None` if it should stay silent.
    /// Pass a goal ID for a goal reminder, or `None` for the daily summary.
    pub fn reminder_text(
        &self,
        goal_id: Option<String>,
        today: String,
    ) -> Result<Option<String>, CoreError> {
        reminders::reminder_text(&self.conn(), goal_id.as_deref(), &today)
    }

    // Export

    pub fn export_csv(&self, dest_path: String) -> Result<(), CoreError> {
        export::export_csv(&self.conn(), &dest_path)
    }

    // Backup

    pub fn export_backup(&self, dest_path: String) -> Result<(), CoreError> {
        backup::export(&self.conn(), &dest_path)
    }

    pub fn restore_backup(&self, backup_path: String) -> Result<(), CoreError> {
        let mut guard = self.conn();
        // The old connection is handed over so the file can be closed and replaced.
        let current = std::mem::replace(&mut *guard, Connection::open_in_memory()?);
        let (conn, result) = backup::restore(current, &self.db_path, &backup_path);
        *guard = conn;
        result
    }

    /// Validates a backup file and returns what it holds, without restoring it.
    pub fn preview_backup(&self, backup_path: String) -> Result<BackupPreview, CoreError> {
        backup::preview(&self.db_path, &backup_path)
    }

    /// The same counts for the data on the phone now, for the "Backup vs Now" table.
    pub fn data_summary(&self) -> Result<BackupPreview, CoreError> {
        backup::summarize(&self.conn())
    }

    /// Reads a preference stored in the database ("theme" or "accent"), so backups keep it.
    pub fn get_pref(&self, key: String) -> Result<Option<String>, CoreError> {
        backup::get_pref(&self.conn(), &key)
    }

    pub fn set_pref(&self, key: String, value: String) -> Result<(), CoreError> {
        backup::set_pref(&self.conn(), &key, &value)
    }
}

#[cfg(test)]
mod tests;
