//! Rules, storage, stats, and rewards for the Goal Tracker app.
//!
//! `GoalCore` is the only type Android talks to. Every method takes the phone's
//! local date from the caller, and each change runs in a single transaction.

mod backup;
mod dates;
mod db;
mod goals;
mod model;
mod rewards;
mod stats;

use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::Connection;

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

    pub fn today(&self, today: String) -> Result<Vec<TodayItem>, CoreError> {
        goals::today_items(&self.conn(), &today)
    }

    // Logging

    pub fn toggle_check_in(&self, goal_id: String, today: String) -> Result<LogResult, CoreError> {
        goals::toggle_check_in(&mut self.conn(), &goal_id, &today)
    }

    pub fn log_amount(
        &self,
        goal_id: String,
        amount: f64,
        today: String,
    ) -> Result<LogResult, CoreError> {
        goals::log_amount(&mut self.conn(), &goal_id, amount, &today)
    }

    pub fn delete_entry(&self, entry_id: String, today: String) -> Result<(), CoreError> {
        goals::delete_entry(&mut self.conn(), &entry_id, &today)
    }

    pub fn list_entries(&self, goal_id: String, limit: u32) -> Result<Vec<Entry>, CoreError> {
        goals::list_entries(&self.conn(), &goal_id, limit)
    }

    // Milestones

    pub fn list_milestones(&self, goal_id: String) -> Result<Vec<Milestone>, CoreError> {
        goals::list_milestones(&self.conn(), &goal_id)
    }

    pub fn add_milestone(&self, goal_id: String, title: String) -> Result<(), CoreError> {
        goals::add_milestone(&self.conn(), &goal_id, &title)
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
}

#[cfg(test)]
mod tests;
