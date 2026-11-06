//! Tests of the public API, using real SQLite files.

use super::*;

const DAY: &str = "2026-11-05";

fn core() -> (Arc<GoalCore>, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("goals.db").to_str().unwrap().to_string();
    (GoalCore::open(path).unwrap(), dir)
}

fn base(title: &str, kind: GoalKind) -> NewGoal {
    NewGoal {
        title: title.into(),
        notes: String::new(),
        kind,
        category: None,
        period: None,
        period_target: None,
        total_target: None,
        unit: None,
        deadline: None,
        milestones: vec![],
        weekdays: None,
    }
}

fn yes_no(core: &GoalCore, title: &str) -> String {
    core.create_goal(base(title, GoalKind::YesNo), DAY.into())
        .unwrap()
}

fn habit(core: &GoalCore, title: &str, period: Period, target: f64, created: &str) -> String {
    let goal = NewGoal {
        period: Some(period),
        period_target: Some(target),
        ..base(title, GoalKind::Habit)
    };
    core.create_goal(goal, created.into()).unwrap()
}

fn numeric(core: &GoalCore, period: Option<(Period, f64)>, total: Option<f64>) -> String {
    let goal = NewGoal {
        period: period.map(|p| p.0),
        period_target: period.map(|p| p.1),
        total_target: total,
        unit: Some("km".into()),
        ..base("Run", GoalKind::Numeric)
    };
    core.create_goal(goal, DAY.into()).unwrap()
}

fn tick(core: &GoalCore, id: &str, date: &str) -> LogResult {
    core.toggle_check_in(id.into(), date.into()).unwrap()
}

fn titles(core: &GoalCore, date: &str) -> Vec<String> {
    core.today(date.into())
        .unwrap()
        .into_iter()
        .map(|i| i.title)
        .collect()
}

// Goals

#[test]
fn rejects_invalid_goals() {
    let (core, _dir) = core();
    let cases = [
        base("  ", GoalKind::YesNo),
        NewGoal {
            total_target: Some(5.0),
            ..base("x", GoalKind::YesNo)
        },
        base("habit without a target", GoalKind::Habit),
        NewGoal {
            period: Some(Period::Day),
            period_target: Some(2.0),
            ..base("x", GoalKind::Habit)
        },
        NewGoal {
            period: Some(Period::Week),
            period_target: Some(8.0),
            ..base("x", GoalKind::Habit)
        },
        NewGoal {
            period: Some(Period::Month),
            period_target: Some(29.0),
            ..base("x", GoalKind::Habit)
        },
        NewGoal {
            period: Some(Period::Week),
            period_target: Some(1.5),
            ..base("x", GoalKind::Habit)
        },
        NewGoal {
            total_target: Some(-1.0),
            ..base("x", GoalKind::Numeric)
        },
        NewGoal {
            total_target: Some(f64::NAN),
            ..base("x", GoalKind::Numeric)
        },
        NewGoal {
            period: Some(Period::Week),
            ..base("x", GoalKind::Numeric)
        },
        NewGoal {
            deadline: Some("soon".into()),
            ..base("x", GoalKind::YesNo)
        },
    ];
    for case in cases {
        assert!(
            core.create_goal(case.clone(), DAY.into()).is_err(),
            "{case:?}"
        );
    }
    assert!(core.list_goals(false).unwrap().is_empty());
}

#[test]
fn edits_keep_history_and_clean_blank_optionals() {
    let (core, _dir) = core();
    let id = yes_no(&core, "Old");
    tick(&core, &id, DAY);
    let edit = GoalEdit {
        title: " New ".into(),
        notes: "n".into(),
        category: Some("  ".into()),
        unit: None,
        deadline: Some("2027-01-01".into()),
    };
    core.update_goal(id.clone(), edit).unwrap();
    let goal = core.get_goal(id).unwrap();
    assert_eq!(goal.title, "New");
    assert_eq!(goal.category, None);
    assert!(goal.done);
}

#[test]
fn archive_hides_blocks_logging_and_restores_with_history() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, DAY);
    tick(&core, &id, DAY);
    core.set_archived(id.clone(), true).unwrap();
    assert!(titles(&core, DAY).is_empty());
    assert_eq!(core.list_goals(true).unwrap().len(), 1);
    assert!(core.toggle_check_in(id.clone(), DAY.into()).is_err());
    assert!(core.stats_overview(DAY.into()).unwrap().goals.is_empty());
    core.set_archived(id.clone(), false).unwrap();
    assert_eq!(core.list_entries(id, 10).unwrap().len(), 1);
}

#[test]
fn deleting_a_goal_removes_its_history() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = yes_no(&core, "Temp");
    tick(&core, &id, DAY);
    core.delete_goal(id.clone()).unwrap();
    assert!(core.get_goal(id.clone()).is_err());
    assert!(core.list_entries(id, 10).unwrap().is_empty());
    assert!(core.list_draws().unwrap().is_empty());
}

// Logging and Today

#[test]
fn yes_no_stays_on_today_only_on_the_day_it_was_ticked() {
    let (core, _dir) = core();
    let id = yes_no(&core, "File taxes");
    assert_eq!(titles(&core, DAY), ["File taxes"]);
    tick(&core, &id, DAY);
    let item = &core.today(DAY.into()).unwrap()[0];
    assert!(item.complete && item.logged_today);
    assert!(titles(&core, "2026-11-06").is_empty());
}

#[test]
fn yes_no_tick_can_be_undone_on_a_later_day() {
    let (core, _dir) = core();
    let id = yes_no(&core, "File taxes");
    tick(&core, &id, DAY);
    tick(&core, &id, "2026-11-07");
    assert!(!core.get_goal(id).unwrap().done);
    assert_eq!(titles(&core, "2026-11-07"), ["File taxes"]);
}

#[test]
fn weekly_habit_leaves_today_once_met_and_returns_next_week() {
    let (core, _dir) = core();
    let id = habit(&core, "Gym", Period::Week, 2.0, "2026-11-02");
    tick(&core, &id, "2026-11-02");
    assert_eq!(titles(&core, "2026-11-03"), ["Gym"]);
    tick(&core, &id, "2026-11-03");
    assert_eq!(titles(&core, "2026-11-03"), ["Gym"]); // Logged today, so undo stays reachable.
    assert!(titles(&core, "2026-11-04").is_empty());
    assert_eq!(titles(&core, "2026-11-09"), ["Gym"]);
}

#[test]
fn habit_allows_one_check_in_per_day_and_toggles_off() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, DAY);
    tick(&core, &id, DAY);
    assert_eq!(core.list_entries(id.clone(), 10).unwrap().len(), 1);
    tick(&core, &id, DAY);
    assert!(core.list_entries(id, 10).unwrap().is_empty());
}

#[test]
fn numeric_rejects_bad_amounts_and_adds_up_decimals() {
    let (core, _dir) = core();
    let id = numeric(&core, None, Some(0.3));
    for bad in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        assert!(core.log_amount(id.clone(), bad, DAY.into()).is_err());
    }
    assert!(core.toggle_check_in(id.clone(), DAY.into()).is_err());
    core.log_amount(id.clone(), 0.1, DAY.into()).unwrap();
    core.log_amount(id.clone(), 0.2, DAY.into()).unwrap();
    assert!(core.get_goal(id).unwrap().done);
}

#[test]
fn numeric_without_targets_is_always_on_today() {
    let (core, _dir) = core();
    let id = numeric(&core, None, None);
    core.log_amount(id, 5.0, DAY.into()).unwrap();
    let item = &core.today("2026-11-06".into()).unwrap()[0];
    assert!(!item.complete && !item.logged_today);
}

#[test]
fn overdue_only_applies_to_unfinished_goals_past_their_deadline() {
    let (core, _dir) = core();
    let goal = NewGoal {
        deadline: Some(DAY.into()),
        ..base("Late", GoalKind::YesNo)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    assert!(!core.today(DAY.into()).unwrap()[0].overdue);
    assert!(core.today("2026-11-06".into()).unwrap()[0].overdue);
    tick(&core, &id, "2026-11-06");
    assert!(!core.today("2026-11-06".into()).unwrap()[0].overdue);
}

#[test]
fn ticking_a_goal_does_not_move_it_on_today() {
    let (core, _dir) = core();
    let first = yes_no(&core, "First");
    yes_no(&core, "Second");
    tick(&core, &first, DAY);
    assert_eq!(titles(&core, DAY), ["First", "Second"]);
}

// Milestones

#[test]
fn milestones_keep_order_and_can_be_moved() {
    let (core, _dir) = core();
    let goal = NewGoal {
        milestones: vec!["A".into(), "B".into()],
        ..base("G", GoalKind::YesNo)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    core.add_milestone(id.clone(), "C".into(), DAY.into())
        .unwrap();
    let order = |core: &GoalCore| -> Vec<String> {
        core.list_milestones(id.clone())
            .unwrap()
            .into_iter()
            .map(|m| m.title)
            .collect()
    };
    assert_eq!(order(&core), ["A", "B", "C"]);
    let c = core.list_milestones(id.clone()).unwrap()[2].id.clone();
    core.move_milestone(c.clone(), true).unwrap();
    assert_eq!(order(&core), ["A", "C", "B"]);
    core.move_milestone(c.clone(), true).unwrap();
    core.move_milestone(c.clone(), true).unwrap(); // Already first, so nothing changes.
    assert_eq!(order(&core), ["C", "A", "B"]);
    assert_eq!(
        core.today(DAY.into()).unwrap()[0].next_milestone.as_deref(),
        Some("C")
    );
    core.toggle_milestone(c, DAY.into()).unwrap();
    assert_eq!(
        core.today(DAY.into()).unwrap()[0].next_milestone.as_deref(),
        Some("A")
    );
}

// Heatmap

#[test]
fn heatmap_fills_gaps_and_can_cover_one_or_all_goals() {
    let (core, _dir) = core();
    let a = habit(&core, "A", Period::Day, 1.0, DAY);
    let b = habit(&core, "B", Period::Day, 1.0, DAY);
    tick(&core, &a, DAY);
    tick(&core, &b, DAY);
    tick(&core, &a, "2026-11-07");
    let counts = |goal: Option<String>| -> Vec<u32> {
        core.heatmap(goal, DAY.into(), "2026-11-07".into())
            .unwrap()
            .iter()
            .map(|d| d.count)
            .collect()
    };
    assert_eq!(counts(None), [2, 0, 1]);
    assert_eq!(counts(Some(b.clone())), [1, 0, 0]);
    core.set_archived(b.clone(), true).unwrap();
    assert_eq!(counts(None), [1, 0, 1]);
    assert_eq!(counts(Some(b)), [1, 0, 0]);
    assert!(core.heatmap(None, "2026-11-07".into(), DAY.into()).is_err());
}

// Rewards

fn completions(core: &GoalCore) -> i64 {
    core.conn()
        .query_row("SELECT COUNT(*) FROM target_completions", [], |r| r.get(0))
        .unwrap()
}

/// Ticks fresh yes/no goals until one draws a treat, returning its goal ID.
fn win_one(core: &GoalCore) -> String {
    for n in 0..10 {
        let id = yes_no(core, &format!("Goal {n}"));
        tick(core, &id, DAY);
        if !core.unseen_draws().unwrap().is_empty() {
            return id;
        }
    }
    panic!("the pity rule should guarantee a draw within 10 rolls");
}

#[test]
fn draw_is_guaranteed_within_ten_rolls_for_any_seed() {
    for _ in 0..30 {
        let (core, _dir) = core();
        core.add_treat("Coffee".into()).unwrap();
        win_one(&core);
    }
}

#[test]
fn draw_rate_is_about_one_in_five() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let mut conn = core.conn();
    let tx = conn.transaction().unwrap();
    let rounds = 4000;
    for n in 0..rounds {
        tx.execute(
            "INSERT INTO goals (id, title, kind, created_date) VALUES (?1, 'g', 'yes_no', ?2)",
            rusqlite::params![n.to_string(), DAY],
        )
        .unwrap();
        rewards::complete_target(&tx, &n.to_string(), "done", false, DAY).unwrap();
    }
    let draws: i64 = tx
        .query_row("SELECT COUNT(*) FROM reward_draws", [], |r| r.get(0))
        .unwrap();
    let rate = draws as f64 / f64::from(rounds);
    // The pity rule lifts the true rate from 20% to about 22.4%.
    assert!((0.19..0.26).contains(&rate), "rate was {rate}");
}

#[test]
fn comeback_threshold_doubles_the_odds_and_rolls_are_uniform() {
    let (threshold, boosted) = (
        rewards::test_threshold(false),
        rewards::test_threshold(true),
    );
    assert_eq!(boosted, threshold * 2);
    let rolls: Vec<u64> = (0..20_000)
        .map(|n| rewards::test_roll("seed", &n.to_string(), "done"))
        .collect();
    let share =
        |limit: u64| rolls.iter().filter(|r| **r < limit).count() as f64 / rolls.len() as f64;
    assert!((share(threshold) - 0.2).abs() < 0.02);
    assert!((share(boosted) - 0.4).abs() < 0.02);
}

fn last_comeback_flag(core: &GoalCore) -> bool {
    core.list_draws().unwrap()[0].comeback
}

#[test]
fn comeback_applies_only_after_a_missed_period() {
    // A single-treat jar with forced pity makes the next completion always draw.
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    let force_pity = |core: &GoalCore, n: u32| {
        let conn = core.conn();
        for i in 0..9 {
            conn.execute(
                "INSERT INTO target_completions (id, goal_id, target_key, local_date, outcome, seq)
                 VALUES (?1, ?2, ?3, '2026-01-01', 'none', ?4)",
                rusqlite::params![format!("f{n}{i}"), id, format!("f{n}{i}"), 1000 * n + i],
            )
            .unwrap();
        }
    };

    // Nov 1 is the creation day, so leaving it unmet isn't a comeback.
    force_pity(&core, 1);
    tick(&core, &id, "2026-11-02");
    assert!(!last_comeback_flag(&core));

    // Nov 2 was met, so Nov 3 isn't a comeback either.
    force_pity(&core, 2);
    tick(&core, &id, "2026-11-03");
    assert!(!last_comeback_flag(&core));

    // Nov 4 was missed, so Nov 5 is.
    force_pity(&core, 3);
    tick(&core, &id, "2026-11-05");
    assert!(last_comeback_flag(&core));
}

#[test]
fn each_target_pays_out_once_however_often_it_is_logged() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = numeric(&core, Some((Period::Day, 1.0)), None);
    for _ in 0..5 {
        core.log_amount(id.clone(), 5.0, DAY.into()).unwrap();
    }
    assert_eq!(completions(&core), 1);
}

#[test]
fn one_log_can_complete_period_and_total_targets() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = numeric(&core, Some((Period::Day, 1.0)), Some(5.0));
    core.log_amount(id, 5.0, DAY.into()).unwrap();
    assert_eq!(completions(&core), 2);
}

#[test]
fn undo_removes_the_reward_even_if_redeemed() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = win_one(&core);
    let draw = core.list_draws().unwrap()[0].id.clone();
    core.toggle_redeemed(draw, DAY.into()).unwrap();
    tick(&core, &id, DAY);
    assert!(core.list_draws().unwrap().is_empty());
}

#[test]
fn undo_then_relog_gives_the_same_result() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    core.add_treat("Movie".into()).unwrap();
    let id = win_one(&core);
    let before = core.list_draws().unwrap()[0].treat_title.clone();
    tick(&core, &id, DAY);
    tick(&core, &id, DAY);
    assert_eq!(core.list_draws().unwrap()[0].treat_title, before);
}

#[test]
fn repeated_undo_cannot_farm_a_losing_target_into_a_win() {
    for _ in 0..20 {
        let (core, _dir) = core();
        core.add_treat("Coffee".into()).unwrap();
        let id = yes_no(&core, "Goal");
        tick(&core, &id, DAY);
        let first = core.list_draws().unwrap().len();
        for _ in 0..30 {
            tick(&core, &id, DAY);
            tick(&core, &id, DAY);
            assert_eq!(core.list_draws().unwrap().len(), first);
            assert_eq!(completions(&core), 1);
        }
    }
}

#[test]
fn deleting_the_entry_that_met_a_target_removes_only_that_target() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = numeric(&core, Some((Period::Day, 3.0)), None);
    core.log_amount(id.clone(), 2.0, "2026-11-05".into())
        .unwrap();
    core.log_amount(id.clone(), 2.0, "2026-11-05".into())
        .unwrap();
    core.log_amount(id.clone(), 3.0, "2026-11-06".into())
        .unwrap();
    assert_eq!(completions(&core), 2);
    let entries = core.list_entries(id, 10).unwrap();
    let nov5 = entries
        .iter()
        .find(|e| e.local_date == "2026-11-05")
        .unwrap();
    core.delete_entry(nov5.id.clone(), "2026-11-06".into())
        .unwrap();
    assert_eq!(completions(&core), 1);
}

#[test]
fn empty_jar_reports_and_does_not_count_toward_pity() {
    let (core, _dir) = core();
    let id = yes_no(&core, "No treats yet");
    assert!(tick(&core, &id, DAY).jar_empty);
    let misses: i64 = core
        .conn()
        .query_row(
            "SELECT COUNT(*) FROM target_completions WHERE outcome = 'none'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(misses, 0);
}

#[test]
fn rewards_off_means_no_draws_and_no_prompts() {
    let (core, _dir) = core();
    core.set_rewards_enabled(false).unwrap();
    assert!(!core.rewards_enabled().unwrap());
    for n in 0..15 {
        let id = yes_no(&core, &format!("G{n}"));
        assert!(!tick(&core, &id, DAY).jar_empty);
    }
    core.add_treat("Coffee".into()).unwrap();
    let id = yes_no(&core, "After");
    tick(&core, &id, DAY);
    assert!(core.list_draws().unwrap().is_empty());
}

#[test]
fn milestones_are_targets_and_undo_removes_their_reward() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let goal = NewGoal {
        milestones: vec!["A".into()],
        ..base("G", GoalKind::YesNo)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    let milestone = core.list_milestones(id).unwrap()[0].id.clone();
    core.toggle_milestone(milestone.clone(), DAY.into())
        .unwrap();
    assert_eq!(completions(&core), 1);
    core.toggle_milestone(milestone.clone(), DAY.into())
        .unwrap();
    assert_eq!(completions(&core), 0);
    core.toggle_milestone(milestone.clone(), DAY.into())
        .unwrap();
    core.delete_milestone(milestone).unwrap();
    assert_eq!(completions(&core), 0);
}

#[test]
fn removed_treat_leaves_the_jar_but_keeps_past_draws() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    win_one(&core);
    let treat = core.list_treats().unwrap()[0].id.clone();
    core.remove_treat(treat).unwrap();
    assert!(core.list_treats().unwrap().is_empty());
    assert_eq!(core.list_draws().unwrap()[0].treat_title, "Coffee");
}

#[test]
fn draws_stay_unseen_until_marked() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    win_one(&core);
    let draw = core.unseen_draws().unwrap()[0].id.clone();
    core.mark_draw_seen(draw).unwrap();
    assert!(core.unseen_draws().unwrap().is_empty());
    assert_eq!(core.list_draws().unwrap().len(), 1);
}

// Backup

struct Fixture {
    core: Arc<GoalCore>,
    dir: tempfile::TempDir,
    goal: String,
}

impl Fixture {
    fn new() -> Self {
        let (core, dir) = core();
        let goal = yes_no(&core, "Original");
        tick(&core, &goal, DAY);
        Fixture { core, dir, goal }
    }

    fn path(&self, name: &str) -> String {
        self.dir.path().join(name).to_str().unwrap().to_string()
    }

    fn backup(&self) -> String {
        let path = self.path("backup.db");
        self.core.export_backup(path.clone()).unwrap();
        path
    }

    fn assert_data_unchanged(&self) {
        assert!(self.core.get_goal(self.goal.clone()).unwrap().done);
        assert_eq!(self.core.list_goals(false).unwrap().len(), 1);
    }
}

fn assert_invalid_backup(result: Result<(), CoreError>, expected: &str) {
    match result {
        Err(CoreError::InvalidBackup { reason }) => assert!(reason.contains(expected), "{reason}"),
        other => panic!("expected InvalidBackup, got {other:?}"),
    }
}

#[test]
fn backup_round_trip_restores_all_data_and_rewards() {
    let fixture = Fixture::new();
    fixture.core.add_treat("Coffee".into()).unwrap();
    win_one(&fixture.core);
    let draws = fixture.core.list_draws().unwrap();
    let path = fixture.backup();
    let count = fixture.core.list_goals(false).unwrap().len();

    fixture.core.delete_goal(fixture.goal.clone()).unwrap();
    yes_no(&fixture.core, "Made after the backup");
    fixture.core.restore_backup(path).unwrap();

    assert_eq!(fixture.core.list_goals(false).unwrap().len(), count);
    assert!(fixture.core.get_goal(fixture.goal.clone()).unwrap().done);
    assert_eq!(fixture.core.list_draws().unwrap(), draws);
    assert_eq!(fixture.core.list_treats().unwrap().len(), 1);
    // The app keeps working on the restored file, and the change persists.
    yes_no(&fixture.core, "After restore");
    let db_path = fixture.path("goals.db");
    drop(fixture.core);
    let reopened = GoalCore::open(db_path).unwrap();
    assert_eq!(reopened.list_goals(false).unwrap().len(), count + 1);
}

#[test]
fn backup_is_one_self_contained_file() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    assert!(!std::path::Path::new(&format!("{path}-wal")).exists());
    let conn = Connection::open(&path).unwrap();
    let mode: String = conn
        .query_row("PRAGMA journal_mode", [], |r| r.get(0))
        .unwrap();
    assert_ne!(mode, "wal");
    let goals: i64 = conn
        .query_row("SELECT COUNT(*) FROM goals", [], |r| r.get(0))
        .unwrap();
    assert_eq!(goals, 1);
}

#[test]
fn export_overwrites_an_earlier_backup() {
    let fixture = Fixture::new();
    fixture.backup();
    yes_no(&fixture.core, "Second");
    let path = fixture.backup();
    fixture.core.delete_goal(fixture.goal.clone()).unwrap();
    fixture.core.restore_backup(path).unwrap();
    assert_eq!(fixture.core.list_goals(false).unwrap().len(), 2);
}

#[test]
fn restore_keeps_a_safety_copy() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    yes_no(&fixture.core, "Only in the safety copy");
    fixture.core.restore_backup(path).unwrap();
    let conn = Connection::open(fixture.path("goals.db.before-restore")).unwrap();
    let goals: i64 = conn
        .query_row("SELECT COUNT(*) FROM goals", [], |r| r.get(0))
        .unwrap();
    assert_eq!(goals, 2);
}

#[test]
fn rejects_non_sqlite_and_missing_files() {
    let fixture = Fixture::new();
    let path = fixture.path("junk.db");
    std::fs::write(&path, vec![0x42; 8192]).unwrap();
    assert_invalid_backup(fixture.core.restore_backup(path), "isn't a backup");
    assert!(
        fixture
            .core
            .restore_backup(fixture.path("missing.db"))
            .is_err()
    );
    fixture.assert_data_unchanged();
}

#[test]
fn rejects_truncated_and_corrupted_backups() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    let bytes = std::fs::read(&path).unwrap();

    let truncated = fixture.path("truncated.db");
    std::fs::write(&truncated, &bytes[..bytes.len() / 2]).unwrap();
    assert!(matches!(
        fixture.core.restore_backup(truncated),
        Err(CoreError::InvalidBackup { .. })
    ));

    // Overwrite every page after the header page with noise.
    let mut damaged = bytes.clone();
    for byte in damaged.iter_mut().skip(4096) {
        *byte = 0xA5;
    }
    let corrupted = fixture.path("corrupted.db");
    std::fs::write(&corrupted, damaged).unwrap();
    assert!(matches!(
        fixture.core.restore_backup(corrupted),
        Err(CoreError::InvalidBackup { .. })
    ));
    fixture.assert_data_unchanged();
}

#[test]
fn rejects_other_apps_sqlite_files() {
    let fixture = Fixture::new();
    let path = fixture.path("other.db");
    let conn = Connection::open(&path).unwrap();
    conn.execute_batch("CREATE TABLE goals (id TEXT);").unwrap();
    drop(conn);
    assert_invalid_backup(fixture.core.restore_backup(path), "isn't a Goal Tracker");
    fixture.assert_data_unchanged();
}

#[test]
fn rejects_backups_from_newer_versions() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    Connection::open(&path)
        .unwrap()
        .pragma_update(None, "user_version", 999)
        .unwrap();
    assert_invalid_backup(fixture.core.restore_backup(path), "newer version");
    fixture.assert_data_unchanged();
}

#[test]
fn rejects_backups_with_extra_triggers_views_or_changed_tables() {
    for sql in [
        "CREATE TRIGGER evil AFTER INSERT ON goals BEGIN DELETE FROM goals; END;",
        "CREATE VIEW sneaky AS SELECT * FROM goals;",
        "ALTER TABLE goals ADD COLUMN extra TEXT;",
        "DROP TABLE treats;",
    ] {
        let fixture = Fixture::new();
        let path = fixture.backup();
        let conn = Connection::open(&path).unwrap();
        // Dependent table removed first so the DROP case is allowed.
        if sql.starts_with("DROP") {
            conn.execute_batch("DROP TABLE reward_draws;").unwrap();
        }
        conn.execute_batch(sql).unwrap();
        drop(conn);
        assert_invalid_backup(fixture.core.restore_backup(path), "don't match");
        fixture.assert_data_unchanged();
    }
}

#[test]
fn rejects_backups_with_invalid_dates() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    let conn = Connection::open(&path).unwrap();
    conn.execute("UPDATE entries SET local_date = 'yesterday'", [])
        .unwrap();
    drop(conn);
    assert_invalid_backup(fixture.core.restore_backup(path), "invalid dates");
    fixture.assert_data_unchanged();
}

#[test]
fn failed_restore_leaves_a_working_database() {
    let fixture = Fixture::new();
    let path = fixture.path("junk.db");
    std::fs::write(&path, b"not a database").unwrap();
    assert!(fixture.core.restore_backup(path).is_err());
    let id = yes_no(&fixture.core, "Still works");
    tick(&fixture.core, &id, DAY);
    assert!(fixture.core.get_goal(id).unwrap().done);
}

// Logging past days

#[test]
fn past_days_can_be_logged_but_not_future_or_pre_creation_days() {
    let (core, _dir) = core();
    // DAY is 2026-11-05; the habit starts on 2026-11-01.
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    core.toggle_check_in_on(id.clone(), "2026-11-03".into(), DAY.into())
        .unwrap();
    let entries = core.list_entries(id.clone(), 10).unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].local_date, "2026-11-03");

    assert!(
        core.toggle_check_in_on(id.clone(), "2026-11-06".into(), DAY.into())
            .is_err()
    );
    assert!(
        core.toggle_check_in_on(id.clone(), "2026-10-31".into(), DAY.into())
            .is_err()
    );
    let run = numeric(&core, None, Some(10.0));
    assert!(
        core.log_amount_on(run.clone(), 2.0, "2026-11-06".into(), DAY.into())
            .is_err()
    );
    core.log_amount_on(run, 2.0, "2026-11-05".into(), DAY.into())
        .unwrap();
}

#[test]
fn backfilled_check_in_toggles_off_and_does_not_touch_today() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    let past = "2026-11-03";
    core.toggle_check_in_on(id.clone(), past.into(), DAY.into())
        .unwrap();
    // Same day again removes it (one check-in per day).
    core.toggle_check_in_on(id.clone(), past.into(), DAY.into())
        .unwrap();
    assert!(core.list_entries(id.clone(), 10).unwrap().is_empty());
    // Today stays unticked after a backfill.
    core.toggle_check_in_on(id.clone(), past.into(), DAY.into())
        .unwrap();
    let item = core
        .today(DAY.into())
        .unwrap()
        .into_iter()
        .find(|i| i.goal_id == id)
        .unwrap();
    assert!(!item.logged_today);
}

#[test]
fn backfilled_log_completes_that_period_and_earns_a_reward_once() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    core.toggle_check_in_on(id.clone(), "2026-11-03".into(), DAY.into())
        .unwrap();
    assert_eq!(completions(&core), 1);
    let key: String = core
        .conn()
        .query_row("SELECT target_key FROM target_completions", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(key, "p:2026-11-03");

    // Undo then redo gives the same outcome, so re-logging can't re-roll.
    let outcome = |core: &GoalCore| -> String {
        core.conn()
            .query_row("SELECT outcome FROM target_completions", [], |r| r.get(0))
            .unwrap()
    };
    let first = outcome(&core);
    core.toggle_check_in_on(id.clone(), "2026-11-03".into(), DAY.into())
        .unwrap();
    assert_eq!(completions(&core), 0);
    core.toggle_check_in_on(id, "2026-11-03".into(), DAY.into())
        .unwrap();
    assert_eq!(outcome(&core), first);
}

#[test]
fn backfilling_a_yes_no_goal_ticks_that_day() {
    let (core, _dir) = core();
    let id = yes_no(&core, "Send email");
    core.toggle_check_in_on(id.clone(), DAY.into(), DAY.into())
        .unwrap();
    assert!(core.get_goal(id).unwrap().done);
}

// Skipping and pausing

#[test]
fn skipped_day_keeps_the_streak_and_hides_the_goal_from_today() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    tick(&core, &id, "2026-11-01");
    tick(&core, &id, "2026-11-02");
    core.skip_day(id.clone(), "2026-11-03".into()).unwrap();
    tick(&core, &id, "2026-11-04");
    let stats = core
        .goal_stats(id.clone(), "2026-11-04".into())
        .unwrap()
        .unwrap();
    assert_eq!(stats.current_streak, 3);
    // A skipped day disappears from Today unless something was logged on it.
    core.skip_day(id.clone(), "2026-11-05".into()).unwrap();
    assert!(!titles(&core, "2026-11-05").contains(&"Read".to_string()));
    core.unpause_goal(id.clone(), "2026-11-05".into(), "2026-11-05".into())
        .unwrap();
    assert!(titles(&core, "2026-11-05").contains(&"Read".to_string()));
}

#[test]
fn pause_covers_a_range_and_validates_it() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Week, 3.0, "2026-11-02");
    core.pause_goal(id.clone(), "2026-11-09".into(), "2026-11-15".into())
        .unwrap();
    assert_eq!(core.skipped_days(id.clone()).unwrap().len(), 7);
    // Pausing again changes nothing.
    core.pause_goal(id.clone(), "2026-11-10".into(), "2026-11-11".into())
        .unwrap();
    assert_eq!(core.skipped_days(id.clone()).unwrap().len(), 7);
    assert!(
        core.pause_goal(id.clone(), "2026-11-15".into(), "2026-11-09".into())
            .is_err()
    );
    assert!(
        core.pause_goal(id.clone(), "2026-10-01".into(), "2026-10-05".into())
            .is_err()
    );
    // A week with 3 missed check-ins would normally reset the streak.
    for day in ["2026-11-02", "2026-11-03", "2026-11-04"] {
        tick(&core, &id, day);
    }
    let stats = core.goal_stats(id, "2026-11-23".into()).unwrap().unwrap();
    // 11-09 week excused; 11-16 week missed and over, so the streak resets.
    assert_eq!(stats.current_streak, 0);
    assert_eq!(stats.best_streak, 1);
}

#[test]
fn skipped_days_survive_backup_and_restore() {
    let fixture = Fixture::new();
    let id = habit(&fixture.core, "Read", Period::Day, 1.0, "2026-11-01");
    fixture
        .core
        .skip_day(id.clone(), "2026-11-03".into())
        .unwrap();
    let path = fixture.backup();
    fixture
        .core
        .unpause_goal(id.clone(), "2026-11-03".into(), "2026-11-03".into())
        .unwrap();
    fixture.core.restore_backup(path).unwrap();
    assert_eq!(fixture.core.skipped_days(id).unwrap(), vec!["2026-11-03"]);
}

// Weekday schedules

/// A daily habit due on Monday, Wednesday and Friday. 2026-11-02 is a Monday.
fn mwf(core: &GoalCore) -> String {
    let goal = NewGoal {
        period: Some(Period::Day),
        period_target: Some(1.0),
        weekdays: Some(0b0010101),
        ..base("Gym", GoalKind::Habit)
    };
    core.create_goal(goal, "2026-11-02".into()).unwrap()
}

#[test]
fn weekdays_are_only_allowed_on_daily_habits_and_must_be_valid() {
    let (core, _dir) = core();
    let bad = [
        NewGoal {
            weekdays: Some(1),
            ..base("Task", GoalKind::YesNo)
        },
        NewGoal {
            period: Some(Period::Week),
            period_target: Some(3.0),
            weekdays: Some(1),
            ..base("Weekly", GoalKind::Habit)
        },
        NewGoal {
            period: Some(Period::Day),
            period_target: Some(1.0),
            weekdays: Some(0),
            ..base("Never", GoalKind::Habit)
        },
        NewGoal {
            period: Some(Period::Day),
            period_target: Some(1.0),
            weekdays: Some(128),
            ..base("Bad bit", GoalKind::Habit)
        },
    ];
    for goal in bad {
        assert!(core.create_goal(goal, DAY.into()).is_err());
    }
}

#[test]
fn every_day_is_stored_as_no_schedule() {
    let (core, _dir) = core();
    let goal = NewGoal {
        period: Some(Period::Day),
        period_target: Some(1.0),
        weekdays: Some(127),
        ..base("Daily", GoalKind::Habit)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    assert_eq!(core.get_goal(id).unwrap().weekdays, None);
}

#[test]
fn days_off_do_not_show_on_today_or_break_the_streak() {
    let (core, _dir) = core();
    let id = mwf(&core);
    // Mon 11-02 due, Tue 11-03 off, Wed 11-04 due.
    assert!(titles(&core, "2026-11-02").contains(&"Gym".to_string()));
    assert!(!titles(&core, "2026-11-03").contains(&"Gym".to_string()));
    tick(&core, &id, "2026-11-02");
    tick(&core, &id, "2026-11-04");
    let stats = core
        .goal_stats(id.clone(), "2026-11-04".into())
        .unwrap()
        .unwrap();
    assert_eq!(stats.current_streak, 2);
    // Missing Friday 11-06 breaks it once Saturday arrives, but the weekend does not.
    let stats = core
        .goal_stats(id.clone(), "2026-11-09".into())
        .unwrap()
        .unwrap();
    assert_eq!(stats.current_streak, 0);
    tick(&core, &id, "2026-11-06");
    let stats = core.goal_stats(id, "2026-11-08".into()).unwrap().unwrap();
    assert_eq!(stats.current_streak, 3);
}

#[test]
fn comeback_looks_past_days_off_to_the_last_due_day() {
    let (core, _dir) = core();
    core.add_treat("Coffee".into()).unwrap();
    let id = mwf(&core);
    // Nine misses in a row make the next completion draw, so it shows its flag.
    let force_pity = |n: u32| {
        let conn = core.conn();
        for i in 0..9 {
            conn.execute(
                "INSERT INTO target_completions (id, goal_id, target_key, local_date, outcome, seq)
                 VALUES (?1, ?2, ?3, '2026-01-01', 'none', ?4)",
                rusqlite::params![format!("f{n}{i}"), id, format!("f{n}{i}"), 1000 * n + i],
            )
            .unwrap();
        }
    };
    // Tuesday is a day off, so Wednesday does not follow a missed day.
    force_pity(1);
    tick(&core, &id, "2026-11-04");
    assert!(!last_comeback_flag(&core));
    // Friday 11-06 is missed. The weekend is off, so Monday follows Friday's miss.
    force_pity(2);
    tick(&core, &id, "2026-11-09");
    assert!(last_comeback_flag(&core));
}

// Insights, badges, ordering, export

#[test]
fn period_series_lists_recent_periods_oldest_first() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Week, 2.0, "2026-10-19");
    tick(&core, &id, "2026-10-26");
    tick(&core, &id, "2026-10-27");
    tick(&core, &id, "2026-11-03");
    let series = core
        .period_series(id.clone(), Period::Week, 4, "2026-11-05".into())
        .unwrap();
    let starts: Vec<_> = series.iter().map(|p| p.start.as_str()).collect();
    assert_eq!(starts, ["2026-10-19", "2026-10-26", "2026-11-02"]);
    assert_eq!(series[1].total, 2.0);
    assert!(series[1].met && !series[2].met);
    assert_eq!(series[1].target, Some(2.0));
    // A different period size has no target.
    let days = core
        .period_series(id, Period::Month, 2, "2026-11-05".into())
        .unwrap();
    assert_eq!(days[0].target, None);
}

#[test]
fn streak_runs_list_past_and_current_runs() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    for day in ["2026-11-01", "2026-11-02", "2026-11-04", "2026-11-05"] {
        tick(&core, &id, day);
    }
    let runs = core.streak_runs(id, "2026-11-05".into()).unwrap();
    assert_eq!(runs.len(), 2);
    assert_eq!(
        (runs[0].start.as_str(), runs[0].end.as_str(), runs[0].length),
        ("2026-11-01", "2026-11-02", 2)
    );
    assert_eq!((runs[1].start.as_str(), runs[1].length), ("2026-11-04", 2));
}

#[test]
fn weekday_stats_count_due_days_by_weekday() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-02");
    tick(&core, &id, "2026-11-02"); // Monday met
    // Tuesday missed, Wednesday met, today (Thursday 11-05) still open.
    tick(&core, &id, "2026-11-04");
    let stats = core.weekday_stats("2026-11-05".into()).unwrap();
    assert_eq!((stats[0].met, stats[0].eligible), (1, 1));
    assert_eq!((stats[1].met, stats[1].eligible), (0, 1));
    assert_eq!((stats[2].met, stats[2].eligible), (1, 1));
    assert_eq!(stats[3].eligible, 0);
}

#[test]
fn recap_counts_activity_in_a_week_and_rejects_days() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-10-26");
    tick(&core, &id, "2026-10-28");
    tick(&core, &id, "2026-11-03");
    tick(&core, &id, "2026-11-04");
    let this_week = core.recap(Period::Week, 0, "2026-11-05".into()).unwrap();
    assert_eq!(this_week.start, "2026-11-02");
    assert_eq!(this_week.end, "2026-11-08");
    assert_eq!(
        (
            this_week.entries,
            this_week.active_days,
            this_week.goals_logged
        ),
        (2, 2, 1)
    );
    let last_week = core.recap(Period::Week, 1, "2026-11-05".into()).unwrap();
    assert_eq!(last_week.entries, 1);
    assert!(core.recap(Period::Day, 0, DAY.into()).is_err());
}

#[test]
fn badges_follow_the_data_including_undo() {
    let (core, _dir) = core();
    let earned = |core: &GoalCore, id: &str| {
        core.badges(DAY.into())
            .unwrap()
            .iter()
            .find(|b| b.id == id)
            .unwrap()
            .earned
    };
    assert!(!earned(&core, "first_log"));
    let id = yes_no(&core, "Task");
    tick(&core, &id, DAY);
    assert!(earned(&core, "first_log") && earned(&core, "finished_1"));
    tick(&core, &id, DAY); // undo
    assert!(!earned(&core, "first_log") && !earned(&core, "finished_1"));
}

#[test]
fn goals_can_be_reordered_within_a_category() {
    let (core, _dir) = core();
    let a = yes_no(&core, "A");
    let b = yes_no(&core, "B");
    let c = yes_no(&core, "C");
    let order = |core: &GoalCore| -> Vec<String> {
        core.list_goals(false)
            .unwrap()
            .into_iter()
            .map(|g| g.title)
            .collect()
    };
    assert_eq!(order(&core), ["A", "B", "C"]);
    core.move_goal(c.clone(), true).unwrap();
    assert_eq!(order(&core), ["A", "C", "B"]);
    core.move_goal(a.clone(), false).unwrap();
    assert_eq!(order(&core), ["C", "A", "B"]);
    // Moving past either end changes nothing.
    core.move_goal(c, true).unwrap();
    core.move_goal(b, false).unwrap();
    assert_eq!(order(&core), ["C", "A", "B"]);
}

#[test]
fn milestone_progress_is_reported_on_goals_and_today() {
    let (core, _dir) = core();
    let goal = NewGoal {
        milestones: vec!["Lec 1".into(), "Lec 2".into(), "Lec 3".into()],
        ..base("Prep quiz", GoalKind::YesNo)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    let ms = core.list_milestones(id.clone()).unwrap();
    core.toggle_milestone(ms[0].id.clone(), DAY.into()).unwrap();
    let g = core.get_goal(id).unwrap();
    assert_eq!((g.milestones_done, g.milestones_total), (1, 3));
    let item = &core.today(DAY.into()).unwrap()[0];
    assert_eq!((item.milestones_done, item.milestones_total), (1, 3));
}

#[test]
fn csv_export_quotes_text_and_defuses_formulas() {
    let (core, dir) = core();
    let goal = NewGoal {
        title: "=Run, fast".into(),
        category: Some("Health".into()),
        unit: Some("km".into()),
        ..base("x", GoalKind::Numeric)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    core.log_amount(id, 2.5, DAY.into()).unwrap();
    let path = dir.path().join("out.csv").to_str().unwrap().to_string();
    core.export_csv(path.clone()).unwrap();
    let text = std::fs::read_to_string(path).unwrap();
    assert_eq!(
        text,
        "date,goal,category,kind,amount,unit\n2026-11-05,\"'=Run, fast\",Health,numeric,2.5,km\n"
    );
}

#[test]
fn a_version_one_database_upgrades_and_keeps_its_data() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db").to_str().unwrap().to_string();
    {
        let mut conn = Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 1).unwrap();
        conn.execute(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01')",
            [],
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    let goal = core.get_goal("g1".into()).unwrap();
    assert_eq!((goal.title.as_str(), goal.weekdays), ("Old", None));
    core.skip_day("g1".into(), "2026-01-02".into()).unwrap();
    core.move_goal("g1".into(), true).unwrap();
    core.set_goal_reminder("g1".into(), Some(600)).unwrap();
}

// Reminders

#[test]
fn goal_reminders_can_be_set_changed_cleared_and_listed() {
    let (core, _dir) = core();
    let a = yes_no(&core, "Alpha");
    let b = yes_no(&core, "Beta");
    core.set_goal_reminder(a.clone(), Some(20 * 60)).unwrap();
    core.set_goal_reminder(b.clone(), Some(8 * 60 + 30))
        .unwrap();
    let list = core.list_reminders().unwrap();
    assert_eq!(
        list.iter().map(|r| r.minutes).collect::<Vec<_>>(),
        [510, 1200]
    );
    core.set_goal_reminder(a.clone(), Some(9 * 60)).unwrap();
    assert_eq!(core.goal_reminder(a.clone()).unwrap(), Some(540));
    core.set_goal_reminder(a.clone(), None).unwrap();
    assert_eq!(core.goal_reminder(a).unwrap(), None);
    // Archived goals drop out of the list but keep their time.
    core.set_archived(b.clone(), true).unwrap();
    assert!(core.list_reminders().unwrap().is_empty());
    assert_eq!(core.goal_reminder(b).unwrap(), Some(510));
}

#[test]
fn reminder_times_are_validated_and_removed_with_the_goal() {
    let (core, _dir) = core();
    let id = yes_no(&core, "Task");
    assert!(core.set_goal_reminder(id.clone(), Some(1440)).is_err());
    assert!(core.set_daily_reminder(Some(2000)).is_err());
    assert!(core.set_goal_reminder("missing".into(), Some(60)).is_err());
    core.set_goal_reminder(id.clone(), Some(60)).unwrap();
    core.delete_goal(id).unwrap();
    let rows: i64 = core
        .conn()
        .query_row("SELECT COUNT(*) FROM reminders", [], |r| r.get(0))
        .unwrap();
    assert_eq!(rows, 0);
}

#[test]
fn daily_reminder_round_trips_and_ignores_damaged_values() {
    let (core, _dir) = core();
    assert_eq!(core.daily_reminder().unwrap(), None);
    core.set_daily_reminder(Some(19 * 60)).unwrap();
    assert_eq!(core.daily_reminder().unwrap(), Some(1140));
    core.set_daily_reminder(Some(0)).unwrap();
    assert_eq!(core.daily_reminder().unwrap(), Some(0));
    core.conn()
        .execute(
            "UPDATE meta SET value = 'soon' WHERE key = 'daily_reminder_minutes'",
            [],
        )
        .unwrap();
    assert_eq!(core.daily_reminder().unwrap(), None);
    core.set_daily_reminder(None).unwrap();
    assert_eq!(core.daily_reminder().unwrap(), None);
}

#[test]
fn reminder_text_stays_silent_when_nothing_is_due() {
    let (core, _dir) = core();
    let read = yes_no(&core, "Read");
    let _walk = yes_no(&core, "Walk");
    let daily = core.reminder_text(None, DAY.into()).unwrap().unwrap();
    assert_eq!(daily, "2 goals left today: Read, Walk");
    assert_eq!(
        core.reminder_text(Some(read.clone()), DAY.into())
            .unwrap()
            .unwrap(),
        "Time for Read"
    );
    // Done goals stay quiet, and the summary shrinks.
    tick(&core, &read, DAY);
    assert_eq!(
        core.reminder_text(Some(read.clone()), DAY.into()).unwrap(),
        None
    );
    assert_eq!(
        core.reminder_text(None, DAY.into()).unwrap().unwrap(),
        "1 goal left today: Walk"
    );
}

#[test]
fn reminder_text_skips_days_off_and_skipped_days() {
    let (core, _dir) = core();
    let gym = mwf(&core); // created Monday 2026-11-02; Tuesday is a day off
    assert!(
        core.reminder_text(Some(gym.clone()), "2026-11-03".into())
            .unwrap()
            .is_none()
    );
    assert!(
        core.reminder_text(Some(gym.clone()), "2026-11-04".into())
            .unwrap()
            .is_some()
    );
    core.skip_day(gym.clone(), "2026-11-04".into()).unwrap();
    assert!(
        core.reminder_text(Some(gym), "2026-11-04".into())
            .unwrap()
            .is_none()
    );
    assert!(
        core.reminder_text(None, "2026-11-04".into())
            .unwrap()
            .is_none()
    );
}

#[test]
fn reminders_survive_backup_and_restore() {
    let fixture = Fixture::new();
    fixture
        .core
        .set_goal_reminder(fixture.goal.clone(), Some(7 * 60))
        .unwrap();
    fixture.core.set_daily_reminder(Some(21 * 60)).unwrap();
    let path = fixture.backup();
    fixture
        .core
        .set_goal_reminder(fixture.goal.clone(), None)
        .unwrap();
    fixture.core.set_daily_reminder(None).unwrap();
    fixture.core.restore_backup(path).unwrap();
    assert_eq!(
        fixture.core.goal_reminder(fixture.goal.clone()).unwrap(),
        Some(420)
    );
    assert_eq!(fixture.core.daily_reminder().unwrap(), Some(1260));
}

// Tasks (DAY is Thursday 2026-11-05)

fn new_task(title: &str) -> NewTask {
    NewTask {
        title: title.into(),
        notes: String::new(),
        due: None,
        priority: Priority::Med,
        tag: None,
        repeat: None,
        items: vec![],
        goal_id: None,
    }
}

fn add_task(core: &GoalCore, task: NewTask) -> String {
    core.create_task(task, DAY.into()).unwrap()
}

fn task_titles(core: &GoalCore, filter: TaskFilter, sort: TaskSort) -> Vec<String> {
    core.list_tasks(filter, sort, DAY.into())
        .unwrap()
        .into_iter()
        .map(|t| t.title)
        .collect()
}

#[test]
fn quick_add_reads_date_priority_and_tag() {
    let (core, _dir) = core();
    let parsed = core
        .parse_quick("Essay tomorrow !high #uni".into(), DAY.into())
        .unwrap();
    assert_eq!(parsed.title, "Essay");
    assert_eq!(parsed.due.as_deref(), Some("2026-11-06"));
    assert_eq!(parsed.priority, Some(Priority::High));
    assert_eq!(parsed.tag.as_deref(), Some("uni"));

    let id = core
        .quick_add_task("Essay tomorrow !high #uni".into(), DAY.into())
        .unwrap();
    let task = core.get_task(id, DAY.into()).unwrap();
    assert_eq!(task.title, "Essay");
    assert_eq!(task.due.as_deref(), Some("2026-11-06"));
    assert_eq!(task.priority, Priority::High);
    assert_eq!(task.tag.as_deref(), Some("uni"));
}

#[test]
fn quick_add_understands_the_supported_date_words() {
    let (core, _dir) = core();
    let due = |raw: &str| core.parse_quick(raw.into(), DAY.into()).unwrap().due;
    assert_eq!(due("Call today"), Some("2026-11-05".into()));
    assert_eq!(due("Call in 3 days"), Some("2026-11-08".into()));
    assert_eq!(due("Call in 2 weeks"), Some("2026-11-19".into()));
    // Today is Thursday: next week means the coming Monday, a weekday name can mean today.
    assert_eq!(due("Call next week"), Some("2026-11-09".into()));
    assert_eq!(due("Call thursday"), Some("2026-11-05".into()));
    assert_eq!(due("Call on friday"), Some("2026-11-06".into()));
    assert_eq!(due("Call 2026-12-24"), Some("2026-12-24".into()));
    assert_eq!(due("Call 25 dec"), Some("2026-12-25".into()));
    assert_eq!(due("Call december 25"), Some("2026-12-25".into()));
    assert_eq!(due("Call 25/12"), Some("2026-12-25".into()));
    // Dates already past roll to next year. A fake date stays in the title.
    assert_eq!(due("Call 1 jan"), Some("2027-01-01".into()));
    assert_eq!(due("Call 31 feb"), None);
}

#[test]
fn quick_add_keeps_ordinary_text_and_only_uses_whole_words() {
    let (core, _dir) = core();
    let parsed = core
        .parse_quick("Buy 1/2 cup todayish stuff".into(), DAY.into())
        .unwrap();
    assert_eq!(parsed.title, "Buy 1/2 cup todayish stuff");
    assert_eq!(parsed.due, None);
    // A line made only of tokens keeps its text as the title.
    let only = core.parse_quick("tomorrow".into(), DAY.into()).unwrap();
    assert_eq!(only.title, "tomorrow");
    assert_eq!(only.due.as_deref(), Some("2026-11-06"));
    // Priority shortcuts and an unknown one.
    let p = |raw: &str| core.parse_quick(raw.into(), DAY.into()).unwrap().priority;
    assert_eq!(p("x !1"), Some(Priority::Low));
    assert_eq!(p("x !med"), Some(Priority::Med));
    assert_eq!(p("x !urgent"), None);
}

#[test]
fn creates_edits_and_validates_tasks() {
    let (core, _dir) = core();
    assert!(core.create_task(new_task("  "), DAY.into()).is_err());
    let bad_due = NewTask {
        due: Some("2026-02-30".into()),
        ..new_task("x")
    };
    assert!(core.create_task(bad_due, DAY.into()).is_err());

    let id = add_task(
        &core,
        NewTask {
            notes: " bring ID ".into(),
            tag: Some("#uni".into()),
            items: vec!["Draft".into(), "Edit".into()],
            ..new_task(" Essay ")
        },
    );
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    assert_eq!(task.title, "Essay");
    assert_eq!(task.notes, "bring ID");
    assert_eq!(task.tag.as_deref(), Some("uni"));
    assert_eq!((task.items_done, task.items_total), (0, 2));

    core.update_task(
        id.clone(),
        TaskEdit {
            title: "Essay v2".into(),
            notes: String::new(),
            due: Some("2026-11-10".into()),
            priority: Priority::High,
            tag: None,
            repeat: Some(Repeat::Weekly),
            goal_id: None,
        },
    )
    .unwrap();
    let task = core.get_task(id, DAY.into()).unwrap();
    assert_eq!(task.title, "Essay v2");
    assert_eq!(task.priority, Priority::High);
    assert_eq!(task.repeat, Some(Repeat::Weekly));
    assert_eq!(task.tag, None);
}

#[test]
fn checklist_items_can_be_added_ticked_and_removed() {
    let (core, _dir) = core();
    let id = add_task(&core, new_task("Pack"));
    let a = core.add_task_item(id.clone(), "Charger".into()).unwrap();
    core.add_task_item(id.clone(), "Passport".into()).unwrap();
    core.toggle_task_item(a.clone()).unwrap();

    let items = core.list_task_items(id.clone()).unwrap();
    assert_eq!(
        items
            .iter()
            .map(|i| (i.text.as_str(), i.done))
            .collect::<Vec<_>>(),
        vec![("Charger", true), ("Passport", false)]
    );
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    assert_eq!((task.items_done, task.items_total), (1, 2));

    core.toggle_task_item(a.clone()).unwrap();
    core.delete_task_item(a).unwrap();
    assert_eq!(core.list_task_items(id).unwrap().len(), 1);
    assert!(core.add_task_item("missing".into(), "x".into()).is_err());
}

#[test]
fn filters_and_sorts_tasks() {
    let (core, _dir) = core();
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-01".into()),
            ..new_task("Late")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            priority: Priority::Low,
            ..new_task("Now")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-20".into()),
            priority: Priority::High,
            ..new_task("Later")
        },
    );
    add_task(&core, new_task("Someday"));
    let done = add_task(&core, new_task("Finished"));
    core.toggle_task(done, DAY.into()).unwrap();

    use TaskFilter::*;
    use TaskSort::{Alphabetical, Due, Newest};
    assert_eq!(
        task_titles(&core, All, Due),
        ["Late", "Now", "Later", "Someday", "Finished"]
    );
    assert_eq!(task_titles(&core, Today, Due), ["Now"]);
    assert_eq!(task_titles(&core, Overdue, Due), ["Late"]);
    assert_eq!(task_titles(&core, TaskFilter::Done, Due), ["Finished"]);
    assert_eq!(
        task_titles(&core, All, TaskSort::Priority),
        ["Later", "Late", "Someday", "Now", "Finished"]
    );
    assert_eq!(
        task_titles(&core, All, Newest),
        ["Someday", "Later", "Now", "Late", "Finished"]
    );
    assert_eq!(
        task_titles(&core, All, Alphabetical),
        ["Late", "Later", "Now", "Someday", "Finished"]
    );

    let late = core.list_tasks(Overdue, Due, DAY.into()).unwrap();
    assert!(late[0].overdue);
}

#[test]
fn finishing_a_repeating_task_creates_the_next_and_undo_removes_it() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            repeat: Some(Repeat::Weekly),
            items: vec!["Step".into()],
            ..new_task("Weekly review")
        },
    );
    let step = core.list_task_items(id.clone()).unwrap().remove(0);
    core.toggle_task_item(step.id).unwrap();

    let result = core.toggle_task(id.clone(), DAY.into()).unwrap();
    assert!(result.done);
    assert_eq!(result.next_due.as_deref(), Some("2026-11-12"));
    let open = core
        .list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
        .unwrap();
    assert_eq!(open.len(), 2);
    let next = open.iter().find(|t| !t.done).unwrap();
    assert_eq!(next.due.as_deref(), Some("2026-11-12"));
    // The copy starts with an unticked checklist.
    assert_eq!((next.items_done, next.items_total), (0, 1));

    // Finishing it twice must not create a second copy.
    core.toggle_task(id.clone(), DAY.into()).unwrap();
    let again = core.toggle_task(id, DAY.into()).unwrap();
    assert_eq!(again.next_due.as_deref(), Some("2026-11-12"));
    assert_eq!(
        core.list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
            .unwrap()
            .len(),
        2
    );
}

#[test]
fn undoing_a_repeating_task_keeps_a_copy_that_was_already_finished() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            repeat: Some(Repeat::Daily),
            ..new_task("Water plants")
        },
    );
    core.toggle_task(id.clone(), DAY.into()).unwrap();
    let next = core
        .list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
        .unwrap()
        .into_iter()
        .find(|t| !t.done)
        .unwrap();
    core.toggle_task(next.id.clone(), "2026-11-06".into())
        .unwrap();
    core.toggle_task(id.clone(), DAY.into()).unwrap();
    assert!(core.get_task(next.id, DAY.into()).unwrap().done);
}

#[test]
fn deleted_tasks_go_to_the_trash_and_can_be_restored_or_purged() {
    let (core, _dir) = core();
    let keep = add_task(&core, new_task("Keep"));
    let gone = add_task(&core, new_task("Gone"));
    core.delete_task(gone.clone(), DAY.into()).unwrap();
    assert_eq!(task_titles(&core, TaskFilter::All, TaskSort::Due), ["Keep"]);
    assert_eq!(core.list_trash(DAY.into()).unwrap().len(), 1);
    assert!(
        core.update_task(
            gone.clone(),
            TaskEdit {
                title: "x".into(),
                notes: String::new(),
                due: None,
                priority: Priority::Med,
                tag: None,
                repeat: None,
                goal_id: None,
            }
        )
        .is_err()
    );

    core.restore_task(gone.clone()).unwrap();
    assert_eq!(task_titles(&core, TaskFilter::All, TaskSort::Due).len(), 2);
    assert!(core.restore_task(gone.clone()).is_err());

    core.delete_task(gone, "2026-10-01".into()).unwrap();
    // 29 days later it is still there; at 30 days it goes.
    assert_eq!(core.purge_trash("2026-10-30".into()).unwrap(), 0);
    assert_eq!(core.purge_trash("2026-10-31".into()).unwrap(), 1);
    assert!(core.list_trash(DAY.into()).unwrap().is_empty());
    assert_eq!(core.get_task(keep, DAY.into()).unwrap().title, "Keep");
}

#[test]
fn due_tasks_lists_open_tasks_due_today_or_earlier() {
    let (core, _dir) = core();
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-06".into()),
            ..new_task("Tomorrow")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            priority: Priority::Low,
            ..new_task("Today low")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            priority: Priority::High,
            ..new_task("Today high")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-01".into()),
            ..new_task("Overdue")
        },
    );
    add_task(&core, new_task("No date"));
    let finished = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            ..new_task("Done")
        },
    );
    core.toggle_task(finished, DAY.into()).unwrap();
    let titles: Vec<_> = core
        .due_tasks(DAY.into())
        .unwrap()
        .into_iter()
        .map(|t| t.title)
        .collect();
    assert_eq!(titles, ["Overdue", "Today high", "Today low"]);
}

#[test]
fn tasks_never_earn_rewards_or_change_goal_stats() {
    let (core, _dir) = core();
    core.add_treat("Ice cream".into()).unwrap();
    let before = core.stats_overview(DAY.into()).unwrap();
    for n in 0..12 {
        let id = add_task(&core, new_task(&format!("Task {n}")));
        core.toggle_task(id, DAY.into()).unwrap();
    }
    assert!(core.list_draws().unwrap().is_empty());
    assert_eq!(core.stats_overview(DAY.into()).unwrap(), before);
    assert!(titles(&core, DAY).is_empty());
}

#[test]
fn tasks_survive_a_backup_and_restore() {
    let (core, dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            items: vec!["One".into()],
            ..new_task("Keep me")
        },
    );
    let backup = dir
        .path()
        .join("backup.sqlite")
        .to_str()
        .unwrap()
        .to_string();
    core.export_backup(backup.clone()).unwrap();
    core.delete_task(id.clone(), DAY.into()).unwrap();
    core.purge_trash("2027-01-01".into()).unwrap();
    assert!(core.get_task(id.clone(), DAY.into()).is_err());

    core.restore_backup(backup).unwrap();
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    assert_eq!(task.title, "Keep me");
    assert_eq!(core.list_task_items(id).unwrap().len(), 1);
}

#[test]
fn upgrading_from_schema_5_keeps_goals_and_adds_tasks() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 5).unwrap();
        conn.execute(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old goal', 'yes_no', '2026-01-01')",
            [],
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    assert_eq!(core.list_goals(false).unwrap()[0].title, "Old goal");
    let id = add_task(&core, new_task("New task"));
    assert_eq!(core.get_task(id, DAY.into()).unwrap().title, "New task");
}

#[test]
fn a_backup_with_an_invalid_task_date_is_rejected() {
    let (core, dir) = core();
    add_task(&core, new_task("Fine"));
    let backup = dir.path().join("bad.sqlite").to_str().unwrap().to_string();
    core.export_backup(backup.clone()).unwrap();
    {
        let conn = rusqlite::Connection::open(&backup).unwrap();
        conn.execute("UPDATE tasks SET due = '2026-13-45'", [])
            .unwrap();
    }
    assert!(matches!(
        core.restore_backup(backup),
        Err(CoreError::InvalidBackup { .. })
    ));
    assert_eq!(task_titles(&core, TaskFilter::All, TaskSort::Due), ["Fine"]);
}

// Tasks linked to goals

fn edit_of(task: &Task) -> TaskEdit {
    TaskEdit {
        title: task.title.clone(),
        notes: task.notes.clone(),
        due: task.due.clone(),
        priority: task.priority,
        tag: task.tag.clone(),
        repeat: task.repeat,
        goal_id: task.goal_id.clone(),
    }
}

#[test]
fn a_task_can_be_linked_to_a_goal_and_shows_its_name() {
    let (core, _dir) = core();
    let goal = yes_no(&core, "Learn Rust");
    let linked = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Read chapter 1")
        },
    );
    add_task(&core, new_task("Unrelated"));
    let task = core.get_task(linked.clone(), DAY.into()).unwrap();
    assert_eq!(task.goal_id.as_deref(), Some(goal.as_str()));
    assert_eq!(task.goal_title.as_deref(), Some("Learn Rust"));

    let listed = core.list_goal_tasks(goal.clone(), DAY.into()).unwrap();
    assert_eq!(
        listed.iter().map(|t| t.title.as_str()).collect::<Vec<_>>(),
        ["Read chapter 1"]
    );

    // Unlinking through an edit.
    core.update_task(
        linked.clone(),
        TaskEdit {
            goal_id: None,
            ..edit_of(&task)
        },
    )
    .unwrap();
    assert!(core.list_goal_tasks(goal, DAY.into()).unwrap().is_empty());
}

#[test]
fn a_task_cannot_link_to_a_missing_or_archived_goal() {
    let (core, _dir) = core();
    let missing = NewTask {
        goal_id: Some("nope".into()),
        ..new_task("x")
    };
    assert!(core.create_task(missing, DAY.into()).is_err());

    let goal = yes_no(&core, "Old goal");
    core.set_archived(goal.clone(), true).unwrap();
    let archived = NewTask {
        goal_id: Some(goal),
        ..new_task("y")
    };
    assert!(core.create_task(archived, DAY.into()).is_err());
}

#[test]
fn a_task_keeps_its_link_when_the_goal_is_archived_later_and_the_task_is_edited() {
    let (core, _dir) = core();
    let goal = yes_no(&core, "Goal");
    let id = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Step")
        },
    );
    core.set_archived(goal.clone(), true).unwrap();
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    core.update_task(
        id.clone(),
        TaskEdit {
            title: "Step 2".into(),
            ..edit_of(&task)
        },
    )
    .unwrap();
    assert_eq!(
        core.get_task(id, DAY.into()).unwrap().goal_id.as_deref(),
        Some(goal.as_str())
    );
}

#[test]
fn deleting_a_goal_unlinks_its_tasks_but_keeps_them() {
    let (core, _dir) = core();
    let goal = yes_no(&core, "Doomed");
    let id = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Survivor")
        },
    );
    core.delete_goal(goal).unwrap();
    let task = core.get_task(id, DAY.into()).unwrap();
    assert_eq!(task.goal_id, None);
    assert_eq!(task.goal_title, None);
}

#[test]
fn a_repeating_copy_keeps_the_goal_link() {
    let (core, _dir) = core();
    let goal = yes_no(&core, "Fitness");
    let id = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            due: Some(DAY.into()),
            repeat: Some(Repeat::Daily),
            ..new_task("Stretch")
        },
    );
    core.toggle_task(id, DAY.into()).unwrap();
    let open = core
        .list_goal_tasks(goal.clone(), DAY.into())
        .unwrap()
        .into_iter()
        .filter(|t| !t.done)
        .count();
    assert_eq!(open, 1);
}

#[test]
fn finishing_a_linked_task_does_not_log_the_goal_or_earn_a_reward() {
    let (core, _dir) = core();
    core.add_treat("Ice cream".into()).unwrap();
    let goal = yes_no(&core, "Write thesis");
    let before = core.stats_overview(DAY.into()).unwrap();
    for n in 0..12 {
        let id = add_task(
            &core,
            NewTask {
                goal_id: Some(goal.clone()),
                ..new_task(&format!("Step {n}"))
            },
        );
        core.toggle_task(id, DAY.into()).unwrap();
    }
    assert!(core.list_draws().unwrap().is_empty());
    assert_eq!(core.stats_overview(DAY.into()).unwrap(), before);
    // The goal is still open on Today.
    assert_eq!(titles(&core, DAY), ["Write thesis"]);
    assert!(core.list_entries(goal, 10).unwrap().is_empty());
}

#[test]
fn upgrading_from_schema_6_keeps_tasks_and_adds_the_goal_link() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v6.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 6).unwrap();
        conn.execute(
            "INSERT INTO tasks (id, title, created_date) VALUES ('t1', 'Old task', '2026-01-01')",
            [],
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    let task = core.get_task("t1".into(), DAY.into()).unwrap();
    assert_eq!(task.title, "Old task");
    assert_eq!(task.goal_id, None);
}

#[test]
fn a_linked_task_survives_a_backup_and_restore() {
    let (core, dir) = core();
    let goal = yes_no(&core, "Goal");
    let id = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Linked")
        },
    );
    let backup = dir
        .path()
        .join("linked.sqlite")
        .to_str()
        .unwrap()
        .to_string();
    core.export_backup(backup.clone()).unwrap();
    core.delete_goal(goal.clone()).unwrap();
    core.restore_backup(backup).unwrap();
    assert_eq!(
        core.get_task(id, DAY.into()).unwrap().goal_id.as_deref(),
        Some(goal.as_str())
    );
}

// Reward meter

#[test]
fn reward_progress_starts_with_a_full_countdown_and_the_plain_odds() {
    let (core, _dir) = core();
    let p = core.reward_progress().unwrap();
    assert!(p.enabled);
    assert_eq!(
        (p.treats_in_jar, p.misses_since_draw, p.guaranteed_in),
        (0, 0, 10)
    );
    assert_eq!((p.base_percent, p.comeback_percent), (20, 40));
}

#[test]
fn the_meter_follows_every_roll_and_resets_after_a_treat() {
    let (core, _dir) = core();
    core.add_treat("Ice cream".into()).unwrap();
    let mut expected_misses = 0;
    let mut draws = 0;
    for n in 0..40 {
        let id = yes_no(&core, &format!("Goal {n}"));
        let result = tick(&core, &id, DAY);
        // Every completed target either wins or is reported as "no treat".
        assert_eq!(result.drew + result.no_treat, 1);
        if result.drew == 1 {
            draws += 1;
            expected_misses = 0;
        } else {
            expected_misses += 1;
        }
        let p = core.reward_progress().unwrap();
        assert_eq!(p.misses_since_draw, expected_misses);
        assert_eq!(
            p.guaranteed_in,
            10u32.saturating_sub(expected_misses).max(1)
        );
        // The guarantee: never more than 9 misses in a row.
        assert!(expected_misses < 10);
    }
    assert!(
        draws >= 4,
        "40 targets must win at least 4 treats, got {draws}"
    );
}

#[test]
fn undoing_a_target_that_won_a_treat_reports_the_removed_treat() {
    let (core, _dir) = core();
    core.add_treat("Movie".into()).unwrap();
    for n in 0..30 {
        let id = yes_no(&core, &format!("Goal {n}"));
        let won = tick(&core, &id, DAY);
        if won.drew == 1 {
            let undone = tick(&core, &id, DAY);
            assert_eq!(undone.treats_removed, 1);
            return;
        }
        // Undoing a target that won nothing removes no treat.
        let undone = tick(&core, &id, DAY);
        assert_eq!(undone.treats_removed, 0);
        tick(&core, &id, DAY);
    }
    panic!("no treat was won in 30 targets");
}

#[test]
fn an_empty_jar_or_rewards_off_roll_nothing_and_leave_the_meter_alone() {
    let (core, _dir) = core();
    let id = yes_no(&core, "No jar");
    let result = tick(&core, &id, DAY);
    assert!(result.jar_empty);
    assert_eq!((result.drew, result.no_treat), (0, 0));

    core.add_treat("Treat".into()).unwrap();
    core.set_rewards_enabled(false).unwrap();
    let id = yes_no(&core, "Off");
    let result = tick(&core, &id, DAY);
    assert_eq!(
        (result.jar_empty, result.drew, result.no_treat),
        (false, 0, 0)
    );
    let p = core.reward_progress().unwrap();
    assert!(!p.enabled);
    assert_eq!(p.misses_since_draw, 0);
}

// Momentum (today is 2026-11-05, unmet today never counts: the recent window is
// 24 Oct to 4 Nov and the one before it is 12 to 23 Oct)

const PREV_DAYS: [&str; 12] = [
    "2026-10-12",
    "2026-10-13",
    "2026-10-14",
    "2026-10-15",
    "2026-10-16",
    "2026-10-17",
    "2026-10-18",
    "2026-10-19",
    "2026-10-20",
    "2026-10-21",
    "2026-10-22",
    "2026-10-23",
];
const RECENT_DAYS: [&str; 12] = [
    "2026-10-24",
    "2026-10-25",
    "2026-10-26",
    "2026-10-27",
    "2026-10-28",
    "2026-10-29",
    "2026-10-30",
    "2026-10-31",
    "2026-11-01",
    "2026-11-02",
    "2026-11-03",
    "2026-11-04",
];

fn log_days(core: &GoalCore, goal: &str, days: &[&str]) {
    for day in days {
        core.toggle_check_in_on(goal.into(), (*day).into(), DAY.into())
            .unwrap();
    }
}

fn daily(core: &GoalCore, title: &str) -> String {
    habit(core, title, Period::Day, 1.0, "2026-09-01")
}

#[test]
fn momentum_needs_history_before_it_gives_a_verdict() {
    let (core, _dir) = core();
    assert_eq!(
        core.momentum(DAY.into()).unwrap().status,
        MomentumStatus::NotEnoughData
    );
    let id = habit(&core, "New", Period::Day, 1.0, "2026-11-03");
    log_days(&core, &id, &["2026-11-03", "2026-11-04"]);
    let m = core.momentum(DAY.into()).unwrap();
    assert_eq!(m.status, MomentumStatus::NotEnoughData);
    assert!(m.reason.contains("Not enough history"));
}

#[test]
fn momentum_says_slipping_when_fewer_targets_are_met_than_before() {
    let (core, _dir) = core();
    let id = daily(&core, "Run");
    log_days(&core, &id, &PREV_DAYS);
    log_days(&core, &id, &RECENT_DAYS[..6]);
    let m = core.momentum(DAY.into()).unwrap();
    assert_eq!(m.status, MomentumStatus::Slipping);
    assert_eq!(
        (m.rate_percent, m.prev_rate_percent, m.change_points),
        (50, 100, -50)
    );
    assert_eq!(
        m.reason,
        "Targets met 50%, down from 100%. Biggest drop: Run."
    );
}

#[test]
fn momentum_says_advancing_when_more_targets_are_met_than_before() {
    let (core, _dir) = core();
    let id = daily(&core, "Read");
    log_days(&core, &id, &PREV_DAYS[..6]);
    log_days(&core, &id, &RECENT_DAYS);
    let m = core.momentum(DAY.into()).unwrap();
    assert_eq!(m.status, MomentumStatus::Advancing);
    assert_eq!(m.change_points, 50);
    assert!(m.reason.starts_with("Targets met 100%, up from 50%."));
}

#[test]
fn momentum_is_steady_for_small_changes() {
    let (core, _dir) = core();
    // Two goals, 24 periods per window: one extra target is 4.2 points, under the 5 needed.
    let a = daily(&core, "A");
    let b = daily(&core, "B");
    for id in [&a, &b] {
        log_days(&core, id, &PREV_DAYS[..6]);
        log_days(&core, id, &RECENT_DAYS[..6]);
    }
    log_days(&core, &a, &RECENT_DAYS[6..7]);
    let m = core.momentum(DAY.into()).unwrap();
    assert_eq!(m.status, MomentumStatus::Steady);
    assert_eq!(m.change_points, 4);
    // Two extra targets is 8.3 points, enough to count as advancing.
    log_days(&core, &b, &RECENT_DAYS[6..7]);
    assert_eq!(
        core.momentum(DAY.into()).unwrap().status,
        MomentumStatus::Advancing
    );
}

#[test]
fn momentum_ignores_skipped_days_and_archived_goals() {
    let (core, _dir) = core();
    let id = daily(&core, "Walk");
    log_days(&core, &id, &PREV_DAYS);
    // Skipped days in the recent window are excused instead of counted as misses.
    for day in &RECENT_DAYS[..6] {
        core.skip_day(id.clone(), (*day).into()).unwrap();
    }
    log_days(&core, &id, &RECENT_DAYS[6..]);
    let m = core.momentum(DAY.into()).unwrap();
    assert_ne!(m.status, MomentumStatus::Slipping, "{m:?}");

    let dropped = daily(&core, "Dropped");
    log_days(&core, &dropped, &PREV_DAYS);
    core.set_archived(dropped, true).unwrap();
    let after = core.momentum(DAY.into()).unwrap();
    assert_eq!(after.status, m.status);
}

#[test]
fn goal_and_overview_stats_carry_the_previous_window() {
    let (core, _dir) = core();
    let id = daily(&core, "Run");
    log_days(&core, &id, &PREV_DAYS[..9]);
    log_days(&core, &id, &RECENT_DAYS[..3]);
    let overview = core.stats_overview(DAY.into()).unwrap();
    assert_eq!((overview.met, overview.eligible), (3, 12));
    assert_eq!((overview.prev_met, overview.prev_eligible), (9, 12));
    let stats = core.goal_stats(id, DAY.into()).unwrap().unwrap();
    assert_eq!((stats.prev_met, stats.prev_eligible), (9, 12));
}

// Milestones mirrored as tasks

fn goal_with_milestones(core: &GoalCore, titles: &[&str]) -> String {
    let goal = NewGoal {
        milestones: titles.iter().map(|t| t.to_string()).collect(),
        ..base("Thesis", GoalKind::YesNo)
    };
    core.create_goal(goal, DAY.into()).unwrap()
}

fn milestone_task(core: &GoalCore, goal: &str, title: &str) -> Task {
    core.list_goal_tasks(goal.into(), DAY.into())
        .unwrap()
        .into_iter()
        .find(|t| t.title == title)
        .unwrap_or_else(|| panic!("no task for milestone {title}"))
}

#[test]
fn every_new_milestone_gets_a_task_in_the_tasks_list() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline", "Draft"]);
    core.add_milestone(goal.clone(), "Edit".into(), DAY.into())
        .unwrap();

    let milestones = core.list_milestones(goal.clone()).unwrap();
    assert_eq!(milestones.len(), 3);
    let tasks = core
        .list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
        .unwrap();
    assert_eq!(tasks.len(), 3);
    for m in &milestones {
        let task = tasks
            .iter()
            .find(|t| t.milestone_id.as_deref() == Some(m.id.as_str()))
            .unwrap();
        assert_eq!(task.title, m.title);
        assert_eq!(task.goal_id.as_deref(), Some(goal.as_str()));
        assert_eq!(task.goal_title.as_deref(), Some("Thesis"));
        assert!(!task.done);
    }
}

#[test]
fn ticking_the_milestone_ticks_its_task_and_back() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let milestone = core.list_milestones(goal.clone()).unwrap().remove(0);
    core.toggle_milestone(milestone.id.clone(), DAY.into())
        .unwrap();
    assert!(milestone_task(&core, &goal, "Outline").done);
    core.toggle_milestone(milestone.id, DAY.into()).unwrap();
    assert!(!milestone_task(&core, &goal, "Outline").done);
}

#[test]
fn ticking_the_task_ticks_the_milestone_and_the_milestone_still_rolls_the_reward() {
    let (core, _dir) = core();
    core.add_treat("Pizza".into()).unwrap();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let task = milestone_task(&core, &goal, "Outline");

    let result = core.toggle_task(task.id.clone(), DAY.into()).unwrap();
    assert!(result.done);
    // The milestone is the target: it rolled once, and won or lost.
    assert_eq!(result.reward.drew + result.reward.no_treat, 1);
    assert!(
        core.list_milestones(goal.clone()).unwrap()[0]
            .done_date
            .is_some()
    );

    // Unticking through the task undoes the target, and the treat if there was one.
    let undone = core.toggle_task(task.id, DAY.into()).unwrap();
    assert!(!undone.done);
    assert_eq!(undone.reward.treats_removed, result.reward.drew);
    assert!(core.list_milestones(goal).unwrap()[0].done_date.is_none());
}

#[test]
fn deleting_a_milestone_removes_its_task() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline", "Draft"]);
    let milestone = core.list_milestones(goal.clone()).unwrap().remove(0);
    core.delete_milestone(milestone.id).unwrap();
    let titles: Vec<_> = core
        .list_goal_tasks(goal, DAY.into())
        .unwrap()
        .into_iter()
        .map(|t| t.title)
        .collect();
    assert_eq!(titles, ["Draft"]);
}

#[test]
fn deleting_a_goal_removes_the_tasks_of_its_milestones_but_keeps_other_tasks() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Own task")
        },
    );
    core.delete_goal(goal).unwrap();
    assert_eq!(
        task_titles(&core, TaskFilter::All, TaskSort::Due),
        ["Own task"]
    );
}

#[test]
fn a_milestone_task_cannot_be_trashed_moved_to_another_goal_or_made_to_repeat() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let other = yes_no(&core, "Other");
    let task = milestone_task(&core, &goal, "Outline");

    assert!(core.delete_task(task.id.clone(), DAY.into()).is_err());
    assert!(
        core.update_task(
            task.id.clone(),
            TaskEdit {
                goal_id: Some(other),
                ..edit_of(&task)
            }
        )
        .is_err()
    );
    assert!(
        core.update_task(
            task.id.clone(),
            TaskEdit {
                repeat: Some(Repeat::Daily),
                ..edit_of(&task)
            }
        )
        .is_err()
    );
    assert!(
        core.update_task(
            task.id.clone(),
            TaskEdit {
                goal_id: None,
                ..edit_of(&task)
            }
        )
        .is_err()
    );
    // It is still there.
    assert_eq!(milestone_task(&core, &goal, "Outline").id, task.id);
}

#[test]
fn renaming_a_milestone_task_renames_the_milestone_and_other_fields_stay_on_the_task() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let task = milestone_task(&core, &goal, "Outline");
    core.update_task(
        task.id.clone(),
        TaskEdit {
            title: "Outline v2".into(),
            due: Some("2026-11-20".into()),
            priority: Priority::High,
            ..edit_of(&task)
        },
    )
    .unwrap();
    assert_eq!(
        core.list_milestones(goal.clone()).unwrap()[0].title,
        "Outline v2"
    );
    let after = milestone_task(&core, &goal, "Outline v2");
    assert_eq!(
        (after.due.as_deref(), after.priority),
        (Some("2026-11-20"), Priority::High)
    );
}

#[test]
fn upgrading_from_schema_7_creates_tasks_for_existing_milestones() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v7.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 7).unwrap();
        conn.execute_batch(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01');
             INSERT INTO milestones (id, goal_id, title, position, done_date) VALUES
               ('m1', 'g1', 'First', 0, '2026-02-01'),
               ('m2', 'g1', 'Second', 1, NULL);",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    let tasks = core.list_goal_tasks("g1".into(), DAY.into()).unwrap();
    assert_eq!(tasks.len(), 2);
    let first = tasks.iter().find(|t| t.title == "First").unwrap();
    assert_eq!(first.done_date.as_deref(), Some("2026-02-01"));
    assert_eq!(first.milestone_id.as_deref(), Some("m1"));
    assert!(!tasks.iter().find(|t| t.title == "Second").unwrap().done);
}

#[test]
fn milestone_tasks_survive_a_backup_and_restore() {
    let (core, dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let backup = dir.path().join("ms.sqlite").to_str().unwrap().to_string();
    core.export_backup(backup.clone()).unwrap();
    core.delete_goal(goal.clone()).unwrap();
    core.restore_backup(backup).unwrap();
    assert_eq!(milestone_task(&core, &goal, "Outline").title, "Outline");
}

#[test]
fn the_daily_summary_counts_tasks_and_calls_out_late_ones() {
    let (core, _dir) = core();
    // Tasks alone are enough for a summary.
    assert_eq!(core.reminder_text(None, DAY.into()).unwrap(), None);
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-01".into()),
            ..new_task("Pay rent")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            ..new_task("Call mum")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-09".into()),
            ..new_task("Later")
        },
    );
    add_task(&core, new_task("No date"));
    assert_eq!(
        core.reminder_text(None, DAY.into()).unwrap().unwrap(),
        "2 tasks (1 overdue) left today: Pay rent, Call mum"
    );
    yes_no(&core, "Read");
    assert_eq!(
        core.reminder_text(None, DAY.into()).unwrap().unwrap(),
        "1 goal and 2 tasks (1 overdue) left today: Read, Pay rent, Call mum"
    );
    // A goal's own reminder is not affected by tasks.
    assert_eq!(
        core.reminder_text(Some("nope".into()), DAY.into()).unwrap(),
        None
    );
}

#[test]
fn failure_after_the_swap_rolls_back_to_the_safety_copy() {
    let fixture = Fixture::new();
    let backup_path = fixture.backup();
    yes_no(&fixture.core, "Made after the backup");
    let db_path = fixture.path("goals.db");

    // Take the connection out of the core, as restore_backup does.
    let current = {
        let mut guard = fixture.core.conn();
        std::mem::replace(&mut *guard, Connection::open_in_memory().unwrap())
    };
    let (conn, result) = crate::backup::restore_with(current, &db_path, &backup_path, || {
        Err(CoreError::Database {
            detail: "forced failure".into(),
        })
    });
    assert!(result.is_err());
    *fixture.core.conn() = conn;

    // Both goals are still there, so the pre-restore data came back.
    assert_eq!(fixture.core.list_goals(false).unwrap().len(), 2);
    drop(fixture.core);
    let reopened = GoalCore::open(db_path).unwrap();
    assert_eq!(reopened.list_goals(false).unwrap().len(), 2);
}

#[test]
fn preview_counts_a_backup_without_changing_anything() {
    let fixture = Fixture::new();
    let path = fixture.backup();
    yes_no(&fixture.core, "Only now");
    let preview = fixture.core.preview_backup(path).unwrap();
    assert_eq!(preview.goals, 1);
    assert_eq!(preview.entries, 1);
    assert_eq!(preview.newest_entry.as_deref(), Some(DAY));
    assert_eq!(preview.schema_version, crate::db::SCHEMA_VERSION as u32);
    let now = fixture.core.data_summary().unwrap();
    assert_eq!(now.goals, 2);
    assert!(!std::path::Path::new(&fixture.path("goals.db.preview")).exists());
    assert_eq!(fixture.core.list_goals(false).unwrap().len(), 2);
}

#[test]
fn preview_rejects_bad_files() {
    let fixture = Fixture::new();
    assert!(
        fixture
            .core
            .preview_backup(fixture.path("missing.db"))
            .is_err()
    );
    let junk = fixture.path("junk.db");
    std::fs::write(&junk, vec![0x42; 8192]).unwrap();
    assert!(matches!(
        fixture.core.preview_backup(junk),
        Err(CoreError::InvalidBackup { .. })
    ));
}

#[test]
fn empty_truncated_and_newer_files_get_clear_messages() {
    let fixture = Fixture::new();
    let empty = fixture.path("empty.db");
    std::fs::write(&empty, b"").unwrap();
    assert_invalid_backup(fixture.core.restore_backup(empty), "empty");

    let path = fixture.backup();
    let bytes = std::fs::read(&path).unwrap();
    let cut = fixture.path("cut.db");
    std::fs::write(&cut, &bytes[..bytes.len() / 2]).unwrap();
    assert_invalid_backup(fixture.core.restore_backup(cut), "cut short");

    Connection::open(&path)
        .unwrap()
        .pragma_update(None, "user_version", 999)
        .unwrap();
    assert_invalid_backup(fixture.core.restore_backup(path), "update Jarvest");
    fixture.assert_data_unchanged();
}

#[test]
fn theme_and_accent_travel_with_a_backup() {
    let fixture = Fixture::new();
    assert_eq!(fixture.core.get_pref("theme".into()).unwrap(), None);
    fixture
        .core
        .set_pref("theme".into(), "DARK".into())
        .unwrap();
    fixture
        .core
        .set_pref("accent".into(), "GREEN".into())
        .unwrap();
    let path = fixture.backup();
    fixture
        .core
        .set_pref("theme".into(), "LIGHT".into())
        .unwrap();
    fixture.core.restore_backup(path).unwrap();
    assert_eq!(
        fixture.core.get_pref("theme".into()).unwrap().as_deref(),
        Some("DARK")
    );
    assert!(fixture.core.set_pref("seed".into(), "x".into()).is_err());
}

// Task power: archive, bulk edit, tags, custom repeat, search, trash details

fn open_tasks(core: &GoalCore) -> Vec<String> {
    task_titles(core, TaskFilter::All, TaskSort::Newest)
}

#[test]
fn archived_tasks_leave_the_lists_and_come_back() {
    let (core, _dir) = core();
    let id = add_task(&core, new_task("Old"));
    add_task(&core, new_task("Keep"));
    core.archive_task(id.clone(), DAY.into()).unwrap();
    assert_eq!(open_tasks(&core), ["Keep"]);
    let archived = core.list_archived_tasks(DAY.into()).unwrap();
    assert_eq!(archived.len(), 1);
    assert_eq!(archived[0].archived_date.as_deref(), Some(DAY));
    core.unarchive_task(id).unwrap();
    assert_eq!(open_tasks(&core).len(), 2);
    assert!(core.list_archived_tasks(DAY.into()).unwrap().is_empty());
}

#[test]
fn archived_tasks_do_not_count_in_the_daily_summary() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            ..new_task("Hidden")
        },
    );
    core.archive_task(id, DAY.into()).unwrap();
    assert_eq!(core.reminder_text(None, DAY.into()).unwrap(), None);
}

#[test]
fn milestone_tasks_cannot_be_archived_trashed_moved_or_repeated() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let task = milestone_task(&core, &goal, "Outline");
    assert!(core.archive_task(task.id.clone(), DAY.into()).is_err());
    assert!(core.delete_task(task.id.clone(), DAY.into()).is_err());
    assert!(core.set_task_repeat_rule(task.id.clone(), 2, None).is_err());
    // Bulk edits skip it and say so, but still change the ordinary task.
    let plain = add_task(&core, new_task("Plain"));
    let edit = BulkEdit {
        archive: true,
        ..BulkEdit::default()
    };
    let result = core
        .bulk_update_tasks(vec![task.id.clone(), plain], edit, DAY.into())
        .unwrap();
    assert_eq!((result.changed, result.skipped_milestones), (1, 1));
    assert_eq!(core.list_archived_tasks(DAY.into()).unwrap().len(), 1);
    assert_eq!(milestone_task(&core, &goal, "Outline").archived_date, None);
}

#[test]
fn auto_archive_takes_old_finished_tasks_only() {
    let (core, _dir) = core();
    let old = add_task(&core, new_task("Old done"));
    let fresh = add_task(&core, new_task("Fresh done"));
    add_task(&core, new_task("Open"));
    let goal = goal_with_milestones(&core, &["Step"]);
    let step = milestone_task(&core, &goal, "Step");
    core.toggle_task(old.clone(), "2026-10-01".into()).unwrap();
    core.toggle_task(fresh, DAY.into()).unwrap();
    core.toggle_task(step.id.clone(), "2026-10-01".into())
        .unwrap();
    assert_eq!(core.auto_archive_tasks(7, DAY.into()).unwrap(), 1);
    let archived = core.list_archived_tasks(DAY.into()).unwrap();
    assert_eq!(archived[0].id, old);
    // 0 days means every finished task, but a milestone's task is never archived.
    assert_eq!(core.auto_archive_tasks(0, DAY.into()).unwrap(), 1);
    assert_eq!(milestone_task(&core, &goal, "Step").archived_date, None);
}

#[test]
fn archive_screen_can_trash_finished_tasks() {
    let (core, _dir) = core();
    let done = add_task(&core, new_task("Done"));
    let open = add_task(&core, new_task("Open"));
    core.toggle_task(done, DAY.into()).unwrap();
    let all = core
        .list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
        .unwrap();
    for task in all {
        core.archive_task(task.id, DAY.into()).unwrap();
    }
    assert_eq!(core.trash_archived_done(DAY.into()).unwrap(), 1);
    let left = core.list_archived_tasks(DAY.into()).unwrap();
    assert_eq!(left.len(), 1);
    assert_eq!(left[0].id, open);
    assert_eq!(core.list_trash(DAY.into()).unwrap().len(), 1);
}

#[test]
fn bulk_edit_changes_many_tasks_and_counts_pushes() {
    let (core, _dir) = core();
    let a = add_task(
        &core,
        NewTask {
            due: Some("2026-11-06".into()),
            ..new_task("A")
        },
    );
    let b = add_task(&core, new_task("B"));
    let edit = BulkEdit {
        change_due: true,
        due: Some("2026-11-10".into()),
        priority: Some(Priority::High),
        change_tag: true,
        tag: Some("#uni".into()),
        ..BulkEdit::default()
    };
    let result = core
        .bulk_update_tasks(vec![a.clone(), b.clone()], edit, DAY.into())
        .unwrap();
    assert_eq!(result.changed, 2);
    let task_a = core.get_task(a, DAY.into()).unwrap();
    assert_eq!(task_a.due.as_deref(), Some("2026-11-10"));
    assert_eq!(task_a.priority, Priority::High);
    assert_eq!(task_a.tag.as_deref(), Some("uni"));
    // A had a date that moved later (pushed once). B had none, so it was not "pushed".
    assert_eq!(task_a.pushed_count, 1);
    assert_eq!(core.get_task(b, DAY.into()).unwrap().pushed_count, 0);
}

#[test]
fn bulk_edit_rejects_bad_input() {
    let (core, _dir) = core();
    let a = add_task(&core, new_task("A"));
    let edit = BulkEdit {
        change_due: true,
        due: Some("soon".into()),
        ..BulkEdit::default()
    };
    assert!(
        core.bulk_update_tasks(vec![a.clone()], edit, DAY.into())
            .is_err()
    );
    let edit = BulkEdit {
        change_goal: true,
        goal_id: Some("missing".into()),
        ..BulkEdit::default()
    };
    assert!(core.bulk_update_tasks(vec![a], edit, DAY.into()).is_err());
}

#[test]
fn bulk_done_and_trash_can_be_undone_by_restoring() {
    let (core, _dir) = core();
    let a = add_task(&core, new_task("A"));
    let b = add_task(&core, new_task("B"));
    let ids = vec![a.clone(), b.clone()];
    let done = BulkEdit {
        done: true,
        ..BulkEdit::default()
    };
    let result = core
        .bulk_update_tasks(ids.clone(), done, DAY.into())
        .unwrap();
    assert_eq!(result.changed, 2);
    assert!(core.get_task(a.clone(), DAY.into()).unwrap().done);
    let trash = BulkEdit {
        trash: true,
        ..BulkEdit::default()
    };
    core.bulk_update_tasks(ids, trash, DAY.into()).unwrap();
    assert!(open_tasks(&core).is_empty());
    assert_eq!(core.list_trash(DAY.into()).unwrap().len(), 2);
    core.restore_task(a).unwrap();
    core.restore_task(b).unwrap();
    assert_eq!(open_tasks(&core).len(), 2);
}

#[test]
fn bulk_done_on_a_milestone_task_ticks_the_milestone() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline"]);
    let task = milestone_task(&core, &goal, "Outline");
    let done = BulkEdit {
        done: true,
        ..BulkEdit::default()
    };
    let result = core
        .bulk_update_tasks(vec![task.id.clone()], done, DAY.into())
        .unwrap();
    assert_eq!(result.changed, 1);
    assert!(milestone_task(&core, &goal, "Outline").done);
}

#[test]
fn trash_can_delete_forever_and_be_emptied() {
    let (core, _dir) = core();
    let a = add_task(&core, new_task("A"));
    let b = add_task(&core, new_task("B"));
    let live = add_task(&core, new_task("Live"));
    core.delete_task(a.clone(), DAY.into()).unwrap();
    core.delete_task(b, DAY.into()).unwrap();
    let trashed = core.list_trash(DAY.into()).unwrap();
    assert_eq!(trashed[0].deleted_date.as_deref(), Some(DAY));
    core.delete_task_forever(a).unwrap();
    assert_eq!(core.list_trash(DAY.into()).unwrap().len(), 1);
    // Only trashed tasks can be deleted forever.
    assert!(core.delete_task_forever(live).is_err());
    assert_eq!(core.empty_task_trash().unwrap(), 1);
    assert_eq!(open_tasks(&core), ["Live"]);
}

#[test]
fn tags_are_listed_with_counts_and_ignore_case() {
    let (core, _dir) = core();
    for (title, tag) in [("1", "Uni"), ("2", "uni"), ("3", "home")] {
        add_task(
            &core,
            NewTask {
                tag: Some(tag.into()),
                ..new_task(title)
            },
        );
    }
    let hidden = add_task(
        &core,
        NewTask {
            tag: Some("gone".into()),
            ..new_task("4")
        },
    );
    core.delete_task(hidden, DAY.into()).unwrap();
    let tags = core.list_tags().unwrap();
    let names: Vec<_> = tags
        .iter()
        .map(|t| (t.tag.to_lowercase(), t.open))
        .collect();
    assert_eq!(names, [("home".to_string(), 1), ("uni".to_string(), 2)]);
}

#[test]
fn custom_repeat_every_n_weeks_on_weekdays() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            repeat: Some(Repeat::Weekly),
            ..new_task("Gym")
        },
    );
    // Monday + Thursday, every 2 weeks.
    core.set_task_repeat_rule(id.clone(), 2, Some(1 | 8))
        .unwrap();
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    assert_eq!((task.repeat_every, task.repeat_days), (2, Some(9)));
    let result = core.toggle_task(id, DAY.into()).unwrap();
    assert_eq!(result.next_due.as_deref(), Some("2026-11-16"));
    // The copy keeps the rule.
    let copy = core
        .list_tasks(TaskFilter::All, TaskSort::Due, DAY.into())
        .unwrap()
        .into_iter()
        .find(|t| !t.done)
        .unwrap();
    assert_eq!((copy.repeat_every, copy.repeat_days), (2, Some(9)));
}

#[test]
fn repeat_rule_is_validated_and_cleared_with_the_repeat() {
    let (core, _dir) = core();
    let plain = add_task(&core, new_task("No repeat"));
    assert!(core.set_task_repeat_rule(plain, 2, None).is_err());
    let daily = add_task(
        &core,
        NewTask {
            repeat: Some(Repeat::Daily),
            ..new_task("Daily")
        },
    );
    assert!(core.set_task_repeat_rule(daily.clone(), 0, None).is_err());
    assert!(core.set_task_repeat_rule(daily.clone(), 366, None).is_err());
    assert!(
        core.set_task_repeat_rule(daily.clone(), 3, Some(1))
            .is_err()
    );
    core.set_task_repeat_rule(daily.clone(), 3, None).unwrap();
    // Removing the repeat resets the rule.
    let task = core.get_task(daily.clone(), DAY.into()).unwrap();
    core.update_task(
        daily.clone(),
        TaskEdit {
            repeat: None,
            ..edit_of(&task)
        },
    )
    .unwrap();
    assert_eq!(core.get_task(daily, DAY.into()).unwrap().repeat_every, 1);
}

#[test]
fn editing_the_due_date_later_counts_a_push_back() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            due: Some("2026-11-06".into()),
            ..new_task("Essay")
        },
    );
    let task = core.get_task(id.clone(), DAY.into()).unwrap();
    for due in ["2026-11-08", "2026-11-07", "2026-11-12"] {
        core.update_task(
            id.clone(),
            TaskEdit {
                due: Some(due.into()),
                ..edit_of(&task)
            },
        )
        .unwrap();
    }
    // 06 -> 08 (later), 08 -> 07 (earlier), 07 -> 12 (later).
    assert_eq!(core.get_task(id, DAY.into()).unwrap().pushed_count, 2);
}

#[test]
fn checklist_can_be_replaced_from_text_and_keeps_ticks() {
    let (core, _dir) = core();
    let id = add_task(
        &core,
        NewTask {
            items: vec!["One".into(), "Two".into()],
            ..new_task("List")
        },
    );
    let first = core.list_task_items(id.clone()).unwrap()[0].id.clone();
    core.toggle_task_item(first).unwrap();
    core.replace_task_items(id.clone(), vec!["One".into(), "".into(), "Three".into()])
        .unwrap();
    let items = core.list_task_items(id).unwrap();
    let shown: Vec<_> = items.iter().map(|i| (i.text.as_str(), i.done)).collect();
    assert_eq!(shown, [("One", true), ("Three", false)]);
}

#[test]
fn search_finds_tasks_by_every_field_and_goals_and_marks_archived() {
    let (core, _dir) = core();
    add_task(&core, new_task("Write essay"));
    add_task(
        &core,
        NewTask {
            notes: "ask the tutor".into(),
            ..new_task("Email")
        },
    );
    add_task(
        &core,
        NewTask {
            tag: Some("tutoring".into()),
            ..new_task("Prep")
        },
    );
    add_task(
        &core,
        NewTask {
            items: vec!["call tutor".into()],
            ..new_task("Admin")
        },
    );
    let old = add_task(&core, new_task("Tutor invoice"));
    core.archive_task(old, DAY.into()).unwrap();
    let trashed = add_task(&core, new_task("Tutor secret"));
    core.delete_task(trashed, DAY.into()).unwrap();
    yes_no(&core, "Tutor session");

    let found = core.search("tutor".into(), DAY.into()).unwrap();
    assert_eq!(found.tasks.len(), 4);
    assert_eq!(found.goals.len(), 1);
    // Open tasks come before archived ones, and trashed ones never show.
    assert!(found.tasks.last().unwrap().archived_date.is_some());
    assert!(found.tasks.iter().all(|t| t.title != "Tutor secret"));
    assert!(
        core.search("  ".into(), DAY.into())
            .unwrap()
            .tasks
            .is_empty()
    );
    // % is a plain character, not a wildcard.
    assert!(
        core.search("%".into(), DAY.into())
            .unwrap()
            .tasks
            .is_empty()
    );
}

#[test]
fn search_is_capped_at_thirty_results() {
    let (core, _dir) = core();
    for n in 0..40 {
        add_task(&core, new_task(&format!("Match {n}")));
    }
    let found = core.search("match".into(), DAY.into()).unwrap();
    assert_eq!(found.tasks.len(), 30);
}

#[test]
fn upgrading_from_schema_8_adds_the_archive_columns() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v8.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        // The version just before the archive migration.
        db::migrate_to(&mut conn, 8).unwrap();
        conn.execute_batch(
            "INSERT INTO tasks (id, title, created_date, repeat, done_date)
             VALUES ('t1', 'Old', '2026-01-01', 'weekly', '2026-01-02');",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    let task = core.get_task("t1".into(), DAY.into()).unwrap();
    assert_eq!(
        (
            task.archived_date,
            task.pushed_count,
            task.repeat_every,
            task.repeat_days
        ),
        (None, 0, 1, None)
    );
    core.archive_task("t1".into(), DAY.into()).unwrap();
    assert_eq!(core.list_archived_tasks(DAY.into()).unwrap().len(), 1);
}

#[test]
fn rejects_backups_with_invalid_archive_dates() {
    let (core, dir) = core();
    let id = add_task(&core, new_task("A"));
    core.archive_task(id, DAY.into()).unwrap();
    let path = dir.path().join("bad.sqlite").to_str().unwrap().to_string();
    core.export_backup(path.clone()).unwrap();
    let conn = Connection::open(&path).unwrap();
    conn.execute("UPDATE tasks SET archived_date = 'whenever'", [])
        .unwrap();
    drop(conn);
    assert_invalid_backup(core.restore_backup(path), "invalid dates");
    assert_eq!(core.list_archived_tasks(DAY.into()).unwrap().len(), 1);
}

// Quit habits, week start, folders

fn quit(core: &GoalCore, created: &str) -> String {
    core.create_avoid_goal("No sugar".into(), String::new(), None, created.into())
        .unwrap()
}

/// Logs (or removes) a slip on `day`.
fn slip(core: &GoalCore, id: &str, day: &str, today: &str) -> LogResult {
    core.toggle_check_in_on(id.into(), day.into(), today.into())
        .unwrap()
}

#[test]
fn a_quit_habit_counts_clean_days_and_a_slip_breaks_the_streak() {
    let (core, _dir) = core();
    let id = quit(&core, "2026-11-01");
    let goal = core.get_goal(id.clone()).unwrap();
    assert!(goal.avoid);
    assert_eq!(
        (goal.kind, goal.period),
        (GoalKind::Habit, Some(Period::Day))
    );
    // Clean on 11-01..11-05 (today included): streak 5.
    let stats = core.goal_stats(id.clone(), DAY.into()).unwrap().unwrap();
    assert_eq!((stats.current_streak, stats.best_streak), (5, 5));
    // A slip on 11-03 splits it: 11-04 and 11-05 are clean.
    slip(&core, &id, "2026-11-03", DAY);
    let stats = core.goal_stats(id.clone(), DAY.into()).unwrap().unwrap();
    assert_eq!((stats.current_streak, stats.best_streak), (2, 2));
    // Slipping today leaves a streak of zero. Undoing the slip restores it.
    slip(&core, &id, DAY, DAY);
    let item = core.today(DAY.into()).unwrap().remove(0);
    assert!(item.avoid && item.logged_today && !item.complete);
    assert_eq!(item.current_streak, 0);
    slip(&core, &id, DAY, DAY);
    let item = core.today(DAY.into()).unwrap().remove(0);
    assert!(item.complete && item.current_streak == 2);
}

#[test]
fn a_quit_habit_created_today_is_clean_and_stays_on_today() {
    let (core, _dir) = core();
    let id = quit(&core, DAY);
    let item = core.today(DAY.into()).unwrap().remove(0);
    assert!(item.complete && !item.logged_today);
    assert_eq!(item.current_streak, 1);
    assert_eq!(core.streak_runs(id, DAY.into()).unwrap().len(), 1);
}

#[test]
fn a_skipped_slip_day_keeps_the_quit_streak() {
    let (core, _dir) = core();
    let id = quit(&core, "2026-11-01");
    slip(&core, &id, "2026-11-03", DAY);
    core.pause_goal(id.clone(), "2026-11-03".into(), "2026-11-03".into())
        .unwrap();
    let stats = core.goal_stats(id, DAY.into()).unwrap().unwrap();
    // 11-01, 11-02, 11-04, 11-05: the skipped day is neither clean nor a break.
    assert_eq!(stats.current_streak, 4);
}

#[test]
fn quit_habits_never_earn_rewards_and_undo_is_clean() {
    let (core, _dir) = core();
    core.add_treat("Pizza".into()).unwrap();
    let id = quit(&core, "2026-11-01");
    assert_eq!(slip(&core, &id, "2026-11-02", DAY), LogResult::default());
    assert_eq!(slip(&core, &id, "2026-11-02", DAY), LogResult::default());
    assert!(core.list_draws().unwrap().is_empty());
}

#[test]
fn quit_habits_cannot_be_logged_before_creation_or_in_the_future() {
    let (core, _dir) = core();
    let id = quit(&core, "2026-11-03");
    assert!(
        core.toggle_check_in_on(id.clone(), "2026-11-02".into(), DAY.into())
            .is_err()
    );
    assert!(
        core.toggle_check_in_on(id, "2026-11-06".into(), DAY.into())
            .is_err()
    );
}

#[test]
fn upgrading_from_schema_8_adds_the_avoid_flag_and_backups_keep_it() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v8.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 8).unwrap();
        conn.execute_batch(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01');",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    assert!(!core.get_goal("g1".into()).unwrap().avoid);
    // An exported backup restores with the flag intact.
    let id = quit(&core, DAY);
    let file = dir.path().join("b.sqlite").to_str().unwrap().to_string();
    core.export_backup(file.clone()).unwrap();
    core.delete_goal(id.clone()).unwrap();
    core.restore_backup(file).unwrap();
    assert!(core.get_goal(id).unwrap().avoid);
}

#[test]
fn week_start_changes_how_weeks_are_counted_without_rewriting_history() {
    let (core, _dir) = core();
    assert_eq!(core.week_start(), 1);
    // 2026-10-31 is a Saturday and 2026-11-01 a Sunday. Target: 2 days a week.
    let id = habit(&core, "Gym", Period::Week, 2.0, "2026-10-26");
    slip(&core, &id, "2026-10-31", "2026-11-01");
    slip(&core, &id, "2026-11-01", "2026-11-01");
    // Monday weeks: Saturday and Sunday are the same week, so progress is 2.
    let item = core.today("2026-11-01".into()).unwrap().remove(0);
    assert_eq!(item.period_progress, 2.0);
    // Sunday weeks: Sunday starts a new week with one entry.
    core.set_week_start(7).unwrap();
    assert_eq!(core.week_start(), 7);
    let item = core.today("2026-11-01".into()).unwrap().remove(0);
    assert_eq!(item.period_progress, 1.0);
    // The entries themselves are untouched.
    assert_eq!(core.list_entries(id, 10).unwrap().len(), 2);
    assert!(core.set_week_start(3).is_err());
}

#[test]
fn folders_can_be_renamed_and_ordered() {
    let (core, _dir) = core();
    for (title, cat) in [("A", "Health"), ("B", "Health"), ("C", "Work")] {
        let goal = NewGoal {
            category: Some(cat.into()),
            ..base(title, GoalKind::YesNo)
        };
        core.create_goal(goal, DAY.into()).unwrap();
    }
    core.set_folder_order(vec!["Work".into(), "Health".into()])
        .unwrap();
    core.rename_category("Health".into(), "Fitness".into())
        .unwrap();
    assert_eq!(core.folder_order(), ["Work", "Fitness"]);
    assert_eq!(core.categories().unwrap(), ["Fitness", "Work"]);
    assert!(core.rename_category("Work".into(), "  ".into()).is_err());
}

// Icons, sample data and templates

#[test]
fn icons_can_be_set_cleared_and_are_limited() {
    let (core, _dir) = core();
    let goal = yes_no(&core, "Read");
    let task = add_task(&core, new_task("Call"));
    core.set_goal_icon(goal.clone(), Some("📚".into())).unwrap();
    core.set_task_icon(task.clone(), Some(" ☎️ ".into()))
        .unwrap();
    assert_eq!(
        core.get_goal(goal.clone()).unwrap().icon.as_deref(),
        Some("📚")
    );
    assert_eq!(
        core.get_task(task.clone(), DAY.into())
            .unwrap()
            .icon
            .as_deref(),
        Some("☎️")
    );
    core.set_goal_icon(goal.clone(), None).unwrap();
    assert_eq!(core.get_goal(goal.clone()).unwrap().icon, None);
    assert!(core.set_goal_icon(goal, Some("123456789".into())).is_err());
    assert!(core.set_task_icon("nope".into(), None).is_err());
}

#[test]
fn a_schema_8_database_upgrades_with_icon_and_sample_columns() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v8.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 8).unwrap();
        conn.execute_batch(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01');
             INSERT INTO tasks (id, title, created_date) VALUES ('t1', 'Old task', '2026-01-01');",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    let goal = core.get_goal("g1".into()).unwrap();
    assert!(!goal.sample && goal.icon.is_none());
    let task = core.get_task("t1".into(), DAY.into()).unwrap();
    assert!(!task.sample && task.icon.is_none());
    core.set_goal_icon("g1".into(), Some("⭐".into())).unwrap();
}

#[test]
fn sample_data_is_flagged_earns_nothing_and_clears_cleanly() {
    let (core, _dir) = core();
    let real = yes_no(&core, "Real goal");
    let real_task = add_task(&core, new_task("Real task"));
    assert!(!core.has_sample_data().unwrap());
    core.create_sample_data(DAY.into()).unwrap();
    assert!(core.has_sample_data().unwrap());
    // Asking twice does not double up.
    core.create_sample_data(DAY.into()).unwrap();

    let goals = core.list_goals(false).unwrap();
    assert_eq!(goals.iter().filter(|g| g.sample).count(), 2);
    assert!(goals.iter().any(|g| g.sample && g.total_logged > 0.0));
    // No rewards were rolled for the sample history.
    assert!(core.list_draws().unwrap().is_empty());

    let removed = core.clear_sample_data().unwrap();
    assert_eq!(removed, 4);
    assert!(!core.has_sample_data().unwrap());
    let left = core.list_goals(false).unwrap();
    assert_eq!(left.len(), 1);
    assert_eq!(left[0].id, real);
    assert!(core.get_task(real_task, DAY.into()).is_ok());
}

#[test]
fn keeping_sample_data_makes_it_real() {
    let (core, _dir) = core();
    core.create_sample_data(DAY.into()).unwrap();
    core.keep_sample_data().unwrap();
    assert!(!core.has_sample_data().unwrap());
    assert_eq!(core.clear_sample_data().unwrap(), 0);
    assert_eq!(core.list_goals(false).unwrap().len(), 2);
}

#[test]
fn sample_data_survives_a_backup_and_restore() {
    let (core, dir) = core();
    core.create_sample_data(DAY.into()).unwrap();
    let backup = dir.path().join("s.sqlite").to_str().unwrap().to_string();
    core.export_backup(backup.clone()).unwrap();
    core.clear_sample_data().unwrap();
    core.restore_backup(backup).unwrap();
    assert!(core.has_sample_data().unwrap());
}

#[test]
fn templates_create_goals_and_tasks_and_reject_unknown_keys() {
    let (core, _dir) = core();
    for key in ["get_fit", "learn_skill", "school_year"] {
        assert!(core.create_template(key.into(), DAY.into()).unwrap() >= 4);
    }
    assert!(core.create_template("nope".into(), DAY.into()).is_err());
    let goals = core.list_goals(false).unwrap();
    assert_eq!(goals.len(), 6);
    assert!(goals.iter().all(|g| g.icon.is_some() && !g.sample));
}

// Goal trash

/// Ticks new goals until one wins a treat (the pity rule guarantees one within 10).
fn goal_that_won_a_treat(core: &GoalCore) -> String {
    core.add_treat("Ice cream".into()).unwrap();
    for n in 0..12 {
        let id = yes_no(core, &format!("Lucky {n}"));
        if tick(core, &id, DAY).drew == 1 {
            return id;
        }
    }
    panic!("no treat was won");
}

fn trash(core: &GoalCore, id: &str) {
    core.trash_goal(id.into(), DAY.into()).unwrap();
}

#[test]
fn a_trashed_goal_leaves_today_the_goal_list_and_categories() {
    let (core, _dir) = core();
    let goal = NewGoal {
        category: Some("Health".into()),
        ..base("Run", GoalKind::YesNo)
    };
    let id = core.create_goal(goal, DAY.into()).unwrap();
    yes_no(&core, "Read");
    trash(&core, &id);
    assert_eq!(titles(&core, DAY), vec!["Read"]);
    let listed: Vec<String> = core
        .list_goals(false)
        .unwrap()
        .into_iter()
        .map(|g| g.title)
        .collect();
    assert_eq!(listed, vec!["Read"]);
    assert!(core.list_goals(true).unwrap().is_empty());
    assert!(core.categories().unwrap().is_empty());
    // It cannot be logged while it is in the trash.
    assert!(core.toggle_check_in(id.clone(), DAY.into()).is_err());
    core.restore_goal(id).unwrap();
    assert_eq!(titles(&core, DAY), vec!["Run", "Read"]);
    assert_eq!(core.categories().unwrap(), vec!["Health"]);
}

#[test]
fn a_trashed_goal_is_left_out_of_stats_recap_and_the_heatmap() {
    let (core, _dir) = core();
    let id = habit(&core, "Read", Period::Day, 1.0, "2026-11-01");
    let other = habit(&core, "Walk", Period::Day, 1.0, "2026-11-01");
    tick(&core, &id, "2026-11-03");
    tick(&core, &other, "2026-11-03");
    let before = core.stats_overview(DAY.into()).unwrap();
    assert_eq!(before.goals.len(), 2);
    trash(&core, &id);
    let after = core.stats_overview(DAY.into()).unwrap();
    assert_eq!(after.goals.len(), 1);
    assert!(after.met < before.met);
    let recap = core.recap(Period::Week, 0, DAY.into()).unwrap();
    assert_eq!((recap.entries, recap.goals_logged), (1, 1));
    let days = core
        .heatmap(None, "2026-11-03".into(), "2026-11-03".into())
        .unwrap();
    assert_eq!(days[0].count, 1);
    core.restore_goal(id).unwrap();
    assert_eq!(core.stats_overview(DAY.into()).unwrap().goals.len(), 2);
    assert_eq!(core.recap(Period::Week, 0, DAY.into()).unwrap().entries, 2);
}

#[test]
fn a_trashed_goal_is_left_out_of_badges() {
    let (core, _dir) = core();
    let earned = |core: &GoalCore| {
        core.badges(DAY.into())
            .unwrap()
            .into_iter()
            .find(|b| b.id == "first_log")
            .unwrap()
            .earned
    };
    let id = yes_no(&core, "Read");
    tick(&core, &id, DAY);
    assert!(earned(&core));
    trash(&core, &id);
    assert!(!earned(&core));
    core.restore_goal(id).unwrap();
    assert!(earned(&core));
}

#[test]
fn a_trashed_goal_is_left_out_of_treats_and_reward_progress_until_restored() {
    let (core, _dir) = core();
    let id = goal_that_won_a_treat(&core);
    assert_eq!(core.list_draws().unwrap().len(), 1);
    assert_eq!(core.unseen_draws().unwrap().len(), 1);
    let progress_before = core.reward_progress().unwrap();
    trash(&core, &id);
    assert!(core.list_draws().unwrap().is_empty());
    assert!(core.unseen_draws().unwrap().is_empty());
    // History is preserved: restoring brings the draw and the countdown back.
    core.restore_goal(id).unwrap();
    assert_eq!(core.list_draws().unwrap().len(), 1);
    assert_eq!(core.reward_progress().unwrap(), progress_before);
}

#[test]
fn the_countdown_ignores_misses_from_trashed_goals() {
    let (core, _dir) = core();
    core.add_treat("Ice cream".into()).unwrap();
    // Collect a few misses; a goal that won a treat is not trashed.
    let mut missed = Vec::new();
    for n in 0..3 {
        let id = yes_no(&core, &format!("Goal {n}"));
        let result = tick(&core, &id, DAY);
        if result.no_treat == 1 {
            missed.push(id);
        } else if result.drew > 0 {
            // A win resets the countdown, so earlier misses no longer count.
            missed.clear();
        }
    }
    let before = core.reward_progress().unwrap().misses_since_draw;
    for id in &missed {
        trash(&core, id);
    }
    let after = core.reward_progress().unwrap().misses_since_draw;
    assert_eq!(after, before - missed.len() as u32);
    for id in &missed {
        core.restore_goal(id.clone()).unwrap();
    }
    assert_eq!(core.reward_progress().unwrap().misses_since_draw, before);
}

#[test]
fn a_trashed_goal_is_left_out_of_reminders_and_the_csv_export() {
    let (core, dir) = core();
    let id = yes_no(&core, "Read");
    core.set_goal_reminder(id.clone(), Some(600)).unwrap();
    tick(&core, &id, DAY);
    let other = yes_no(&core, "Walk");
    core.set_goal_reminder(other.clone(), Some(700)).unwrap();
    let csv = |name: &str| {
        let path = dir.path().join(name).to_str().unwrap().to_string();
        core.export_csv(path.clone()).unwrap();
        std::fs::read_to_string(path).unwrap()
    };
    assert!(csv("a.csv").contains("Read"));

    trash(&core, &id);
    let reminders = core.list_reminders().unwrap();
    assert_eq!(reminders.len(), 1);
    assert_eq!(reminders[0].goal_id, other);
    // A goal's own reminder stays silent, and the summary does not name it.
    assert_eq!(
        core.reminder_text(Some(id.clone()), DAY.into()).unwrap(),
        None
    );
    let summary = core.reminder_text(None, DAY.into()).unwrap().unwrap();
    assert!(summary.contains("Walk") && !summary.contains("Read"));
    assert!(!csv("b.csv").contains("Read"));

    // Restoring keeps the reminder time the goal had.
    core.restore_goal(id).unwrap();
    assert_eq!(core.list_reminders().unwrap().len(), 2);
    assert!(csv("c.csv").contains("Read"));
}

#[test]
fn a_trashed_goals_milestones_and_tasks_are_hidden_and_come_back_on_restore() {
    let (core, _dir) = core();
    let goal = goal_with_milestones(&core, &["Outline", "Draft"]);
    let linked = add_task(
        &core,
        NewTask {
            goal_id: Some(goal.clone()),
            ..new_task("Buy paper")
        },
    );
    add_task(&core, new_task("Unrelated"));
    let all = |core: &GoalCore| task_titles(core, TaskFilter::All, TaskSort::Alphabetical);
    assert_eq!(all(&core).len(), 4);

    trash(&core, &goal);
    // Milestone tasks vanish; the ordinary linked task stays, unlinked.
    assert_eq!(all(&core), vec!["Buy paper", "Unrelated"]);
    let task = core.get_task(linked.clone(), DAY.into()).unwrap();
    assert_eq!((task.goal_id, task.goal_title), (None, None));
    assert!(core.list_trash(DAY.into()).unwrap().is_empty());
    assert!(core.toggle_check_in(goal.clone(), DAY.into()).is_err());
    // New tasks cannot be linked to it.
    let nope = NewTask {
        goal_id: Some(goal.clone()),
        ..new_task("Nope")
    };
    assert!(core.create_task(nope, DAY.into()).is_err());

    core.restore_goal(goal.clone()).unwrap();
    assert_eq!(all(&core).len(), 4);
    assert_eq!(core.list_milestones(goal.clone()).unwrap().len(), 2);
    let task = core.get_task(linked, DAY.into()).unwrap();
    assert_eq!(task.goal_id.as_deref(), Some(goal.as_str()));
}

#[test]
fn trash_lists_days_left_and_purges_after_thirty_days() {
    let (core, _dir) = core();
    let old = yes_no(&core, "Old");
    let fresh = yes_no(&core, "Fresh");
    core.trash_goal(old.clone(), "2026-10-01".into()).unwrap();
    core.trash_goal(fresh.clone(), "2026-10-20".into()).unwrap();
    let list = core.list_goal_trash("2026-10-25".into()).unwrap();
    assert_eq!(list.len(), 2);
    assert_eq!((list[0].title.as_str(), list[0].days_left), ("Fresh", 25));
    assert_eq!((list[1].title.as_str(), list[1].days_left), ("Old", 6));
    // Trashing twice is an error, and so is restoring what is not in the trash.
    assert!(core.trash_goal(old.clone(), DAY.into()).is_err());
    assert!(core.restore_goal("nope".into()).is_err());

    assert_eq!(core.purge_goal_trash("2026-10-30".into()).unwrap(), 0);
    assert_eq!(core.purge_goal_trash("2026-10-31".into()).unwrap(), 1);
    assert!(core.get_goal(old).is_err());
    assert!(core.get_goal(fresh).is_ok());
    assert_eq!(core.empty_goal_trash().unwrap(), 1);
    assert!(core.list_goal_trash(DAY.into()).unwrap().is_empty());
}

#[test]
fn deleting_a_goal_forever_removes_its_rewards_and_milestone_tasks_but_keeps_other_tasks() {
    let (core, _dir) = core();
    let id = goal_that_won_a_treat(&core);
    core.add_milestone(id.clone(), "Step".into(), DAY.into())
        .unwrap();
    let linked = add_task(
        &core,
        NewTask {
            goal_id: Some(id.clone()),
            ..new_task("Linked")
        },
    );
    trash(&core, &id);
    core.delete_goal(id.clone()).unwrap();
    assert!(core.list_draws().unwrap().is_empty());
    assert!(core.get_goal(id).is_err());
    // The treat stays in the jar; the ordinary task stays, unlinked.
    assert_eq!(core.list_treats().unwrap().len(), 1);
    let names = task_titles(&core, TaskFilter::All, TaskSort::Alphabetical);
    assert_eq!(names, vec!["Linked"]);
    assert_eq!(core.get_task(linked, DAY.into()).unwrap().goal_id, None);
}

#[test]
fn upgrading_from_the_schema_before_goal_trash_keeps_goals_and_adds_the_column() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir
        .path()
        .join("pre-trash.db")
        .to_str()
        .unwrap()
        .to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, db::goal_trash_version() - 1).unwrap();
        conn.execute_batch(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01');",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    assert_eq!(core.list_goals(false).unwrap().len(), 1);
    core.trash_goal("g1".into(), DAY.into()).unwrap();
    assert!(core.list_goals(false).unwrap().is_empty());
    core.restore_goal("g1".into()).unwrap();
    assert_eq!(core.list_goals(false).unwrap().len(), 1);
}

#[test]
fn trashed_goals_survive_a_backup_and_a_bad_trash_date_is_rejected() {
    let fixture = Fixture::new();
    let id = yes_no(&fixture.core, "Read");
    fixture.core.trash_goal(id, DAY.into()).unwrap();
    let path = fixture.path("trash-backup.sqlite");
    fixture.core.export_backup(path.clone()).unwrap();
    fixture.core.restore_backup(path).unwrap();
    assert_eq!(fixture.core.list_goal_trash(DAY.into()).unwrap().len(), 1);

    let bad = fixture.path("bad-trash.sqlite");
    fixture.core.export_backup(bad.clone()).unwrap();
    let conn = Connection::open(&bad).unwrap();
    conn.execute("UPDATE goals SET deleted_date = 'last week'", [])
        .unwrap();
    drop(conn);
    assert_invalid_backup(fixture.core.restore_backup(bad), "invalid dates");
}

#[test]
fn the_report_counts_tasks_done_and_lists_missed_ones() {
    let (core, _dir) = core();
    let done = add_task(
        &core,
        NewTask {
            due: Some("2026-11-03".into()),
            ..new_task("Finished")
        },
    );
    core.toggle_task(done, "2026-11-03".into()).unwrap();
    for (title, due) in [
        ("Missed one", "2026-11-02"),
        ("Due today", "2026-11-05"),
        ("Last month", "2026-10-20"),
    ] {
        add_task(
            &core,
            NewTask {
                due: Some(due.into()),
                ..new_task(title)
            },
        );
    }
    // A milestone's task is not counted: the milestone is the target.
    goal_with_milestones(&core, &["Outline"]);
    let week = core.recap(Period::Week, 0, DAY.into()).unwrap();
    assert_eq!((week.tasks_done, week.tasks_missed), (1, 1));
    assert_eq!(week.missed_tasks[0].title, "Missed one");
    assert_eq!(week.missed_tasks[0].due, "2026-11-02");
    let month = core.recap(Period::Month, 0, DAY.into()).unwrap();
    assert_eq!(month.tasks_missed, 1);
    let last_month = core.recap(Period::Month, 1, DAY.into()).unwrap();
    assert_eq!(last_month.tasks_missed, 1);
    assert_eq!(last_month.missed_tasks[0].title, "Last month");
}

#[test]
fn the_evening_check_in_counts_what_is_left_and_stays_silent_when_nothing_is() {
    let (core, _dir) = core();
    assert_eq!(core.evening_reminder().unwrap(), None);
    core.set_evening_reminder(Some(21 * 60)).unwrap();
    assert_eq!(core.evening_reminder().unwrap(), Some(1260));
    assert!(core.set_evening_reminder(Some(24 * 60)).is_err());
    assert_eq!(core.evening_text(DAY.into()).unwrap(), None);

    let goal = yes_no(&core, "Read");
    yes_no(&core, "Walk");
    assert_eq!(
        core.evening_text(DAY.into()).unwrap().unwrap(),
        "2 goals left today"
    );
    add_task(
        &core,
        NewTask {
            due: Some(DAY.into()),
            ..new_task("Call mum")
        },
    );
    assert_eq!(
        core.evening_text(DAY.into()).unwrap().unwrap(),
        "2 goals and 1 task left today"
    );
    // Trashed goals are not counted.
    trash(&core, &goal);
    assert_eq!(
        core.evening_text(DAY.into()).unwrap().unwrap(),
        "1 goal and 1 task left today"
    );
    core.set_evening_reminder(None).unwrap();
    assert_eq!(core.evening_reminder().unwrap(), None);
}

// Day score

fn due_today(title: &str, priority: Priority) -> NewTask {
    NewTask {
        due: Some(DAY.into()),
        priority,
        ..new_task(title)
    }
}

#[test]
fn an_empty_day_has_no_level() {
    let (core, _dir) = core();
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!((score.possible, score.earned, score.percent), (0, 0, 0));
    assert_eq!((score.level, score.points_to_next), (DayLevel::None, 0));
}

#[test]
fn levels_follow_the_percent_of_planned_points() {
    let (core, _dir) = core();
    let a = yes_no(&core, "A");
    let b = yes_no(&core, "B");
    let c = yes_no(&core, "C");
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!(
        (score.level, score.possible, score.goals_total),
        (DayLevel::None, 30, 3)
    );
    assert_eq!(score.points_to_next, 1);

    tick(&core, &a, DAY);
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!((score.percent, score.level), (33, DayLevel::Spark));
    // Half of 30 is 15, so one more card (10 points) gets there.
    assert_eq!(score.points_to_next, 5);

    tick(&core, &b, DAY);
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!((score.percent, score.level), (66, DayLevel::Blaze));
    assert_eq!(score.points_to_next, 10);

    tick(&core, &c, DAY);
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!(
        (score.percent, score.level, score.points_to_next),
        (100, DayLevel::Titan, 0)
    );
    assert_eq!((score.goals_done, score.goals_total), (3, 3));
}

#[test]
fn exactly_half_is_blaze() {
    let (core, _dir) = core();
    let a = yes_no(&core, "A");
    yes_no(&core, "B");
    tick(&core, &a, DAY);
    assert_eq!(core.day_score(DAY.into()).unwrap().level, DayLevel::Blaze);
}

#[test]
fn tasks_count_by_priority_and_only_when_due_or_done_today() {
    let (core, _dir) = core();
    let high = add_task(&core, due_today("High", Priority::High));
    add_task(&core, due_today("Low", Priority::Low));
    // Overdue still counts. Due tomorrow and undated tasks are not planned for today.
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-01".into()),
            ..new_task("Late")
        },
    );
    add_task(
        &core,
        NewTask {
            due: Some("2026-11-06".into()),
            ..new_task("Later")
        },
    );
    add_task(&core, new_task("Someday"));
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!((score.possible, score.tasks_total), (15 + 5 + 10, 3));

    core.toggle_task(high, DAY.into()).unwrap();
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!(
        (score.earned, score.tasks_done, score.possible),
        (15, 1, 30)
    );
    assert_eq!((score.percent, score.level), (50, DayLevel::Blaze));
}

#[test]
fn an_undated_task_finished_today_counts_for_today() {
    let (core, _dir) = core();
    let id = add_task(&core, new_task("Quick"));
    assert_eq!(core.day_score(DAY.into()).unwrap().possible, 0);
    core.toggle_task(id, DAY.into()).unwrap();
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!(
        (score.earned, score.possible, score.level),
        (10, 10, DayLevel::Titan)
    );
}

#[test]
fn trashed_tasks_and_milestone_tasks_do_not_count() {
    let (core, _dir) = core();
    let gone = add_task(&core, due_today("Gone", Priority::Med));
    core.delete_task(gone, DAY.into()).unwrap();
    assert_eq!(core.day_score(DAY.into()).unwrap().possible, 0);

    let goal = yes_no(&core, "Goal");
    core.add_milestone(goal, "Step".into(), DAY.into()).unwrap();
    // Only the goal's card is planned: the milestone's mirror task adds nothing.
    let score = core.day_score(DAY.into()).unwrap();
    assert_eq!((score.possible, score.tasks_total), (10, 0));
}

#[test]
fn the_score_rejects_a_bad_date() {
    let (core, _dir) = core();
    assert!(core.day_score("nope".into()).is_err());
}

// Monsters

/// `DAY` plus `n` days, as text.
fn d(n: u64) -> String {
    (chrono::NaiveDate::parse_from_str(DAY, "%Y-%m-%d").unwrap() + chrono::Days::new(n)).to_string()
}

fn daily_habit(core: &GoalCore, title: &str) -> String {
    habit(core, title, Period::Day, 1.0, DAY)
}

fn health_of(core: &GoalCore, monster: &str, today: &str) -> Monster {
    core.list_monsters(today.into())
        .unwrap()
        .into_iter()
        .find(|m| m.id == monster)
        .unwrap()
}

/// Treats won by beating monsters, newest first.
fn monster_draws(core: &GoalCore) -> Vec<RewardDraw> {
    core.list_draws()
        .unwrap()
        .into_iter()
        .filter(|d| d.from_monster)
        .collect()
}

#[test]
fn a_monster_needs_a_name_and_sensible_health() {
    let (core, _dir) = core();
    assert!(core.create_monster("  ".into(), 100, DAY.into()).is_err());
    assert!(core.create_monster("Big".into(), 5, DAY.into()).is_err());
    assert!(
        core.create_monster("Big".into(), 20_000, DAY.into())
            .is_err()
    );
    assert!(
        core.create_monster("x".repeat(61), 100, DAY.into())
            .is_err()
    );
    let id = core
        .create_monster("Sugar".into(), 100, DAY.into())
        .unwrap();
    let monster = health_of(&core, &id, DAY);
    assert_eq!(
        (monster.round, monster.health, monster.max_health),
        (1, 100, 100)
    );
    assert_eq!(monster.status, MonsterStatus::Fighting);
}

#[test]
fn linked_goals_deal_their_power_once_per_day_and_undo_takes_it_back() {
    let (core, _dir) = core();
    let monster = core
        .create_monster("Sugar".into(), 100, DAY.into())
        .unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    assert_eq!(health_of(&core, &monster, DAY).health, 100);
    tick(&core, &goal, DAY);
    assert_eq!(health_of(&core, &monster, DAY).health, 90);
    // Ticking again is an undo, so the damage goes back.
    tick(&core, &goal, DAY);
    assert_eq!(health_of(&core, &monster, DAY).health, 100);
    // A new link power replaces the old one.
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 20)
        .unwrap();
    tick(&core, &goal, DAY);
    assert_eq!(health_of(&core, &monster, DAY).health, 80);
    assert!(
        core.link_monster_item(monster, MonsterLinkKind::Goal, goal, 7)
            .is_err()
    );
}

#[test]
fn finished_tasks_deal_damage() {
    let (core, _dir) = core();
    let monster = core.create_monster("Delay".into(), 50, DAY.into()).unwrap();
    let task = add_task(&core, new_task("Write"));
    core.link_monster_item(monster.clone(), MonsterLinkKind::Task, task.clone(), 20)
        .unwrap();
    core.toggle_task(task, DAY.into()).unwrap();
    assert_eq!(health_of(&core, &monster, DAY).health, 30);
}

#[test]
fn a_beaten_monster_is_recorded_once_pays_a_treat_then_rests_and_respawns_stronger() {
    let (core, _dir) = core();
    core.add_treat("Ice cream".into()).unwrap();
    let monster = core.create_monster("Sugar".into(), 20, DAY.into()).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, &d(0));
    tick(&core, &goal, &d(1));
    let events = core.monster_tick(d(1)).unwrap();
    assert_eq!(events.len(), 1);
    assert_eq!(
        (events[0].round, events[0].final_boss, events[0].prizes),
        (1, false, 1)
    );
    // Recorded once only.
    assert!(core.monster_tick(d(1)).unwrap().is_empty());
    let resting = health_of(&core, &monster, &d(2));
    assert_eq!(resting.status, MonsterStatus::Resting);
    assert_eq!(resting.returns_date, Some(d(5)));
    assert_eq!(core.monster_trophies().unwrap().len(), 1);
    // The prize shows up in the treat list.
    // Ticking a goal can also win an ordinary treat, so look at monster prizes only.
    let draws = monster_draws(&core);
    assert_eq!(draws.len(), 1);
    assert_eq!(draws[0].goal_title, "Sugar");

    // Damage during the rest does not count, and round 2 has a quarter more health (20 -> 25).
    tick(&core, &goal, &d(2));
    assert!(core.monster_tick(d(4)).unwrap().is_empty());
    assert_eq!(
        health_of(&core, &monster, &d(4)).status,
        MonsterStatus::Resting
    );
    core.monster_tick(d(5)).unwrap();
    let round2 = health_of(&core, &monster, &d(5));
    assert_eq!(
        (
            round2.status,
            round2.round,
            round2.max_health,
            round2.health
        ),
        (MonsterStatus::Fighting, 2, 25, 25)
    );
}

#[test]
fn undoing_after_a_win_never_takes_the_trophy_back() {
    let (core, _dir) = core();
    let monster = core.create_monster("Sugar".into(), 10, DAY.into()).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster, MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, DAY);
    assert_eq!(core.monster_tick(DAY.into()).unwrap().len(), 1);
    tick(&core, &goal, DAY);
    assert_eq!(core.monster_trophies().unwrap().len(), 1);
}

#[test]
fn a_slip_heals_half_the_power_and_clean_days_deal_damage() {
    let (core, _dir) = core();
    let monster = core
        .create_monster("Habit".into(), 100, DAY.into())
        .unwrap();
    let quit = core
        .create_avoid_goal("No sugar".into(), String::new(), None, DAY.into())
        .unwrap();
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, quit.clone(), 20)
        .unwrap();
    // Today is not clean until it is over.
    assert_eq!(health_of(&core, &monster, DAY).health, 100);
    assert_eq!(health_of(&core, &monster, &d(1)).health, 80);
    // Clean days 0 and 1, a slip on day 2, and day 3 is today: -20, -20, +10.
    core.toggle_check_in_on(quit, d(2), d(3)).unwrap();
    assert_eq!(health_of(&core, &monster, &d(3)).health, 70);
}

#[test]
fn the_final_boss_is_five_times_stronger_pays_three_treats_and_never_returns() {
    let (core, _dir) = core();
    core.add_treat("Cake".into()).unwrap();
    let monster = core.create_monster("Sugar".into(), 10, DAY.into()).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 20)
        .unwrap();
    core.make_final_boss(monster.clone(), DAY.into()).unwrap();
    let boss = health_of(&core, &monster, DAY);
    assert_eq!(
        (boss.final_boss, boss.max_health, boss.health),
        (true, 50, 50)
    );
    assert!(core.make_final_boss(monster.clone(), DAY.into()).is_err());
    tick(&core, &goal, &d(0));
    tick(&core, &goal, &d(1));
    assert!(core.monster_tick(d(1)).unwrap().is_empty());
    tick(&core, &goal, &d(2));
    let events = core.monster_tick(d(2)).unwrap();
    assert_eq!(
        (events.len(), events[0].final_boss, events[0].prizes),
        (1, true, 3)
    );
    let gone = health_of(&core, &monster, &d(2));
    assert_eq!(gone.status, MonsterStatus::Retired);
    assert!(gone.links.is_empty());
    // No respawn, ever.
    assert!(core.monster_tick(d(60)).unwrap().is_empty());
    assert_eq!(health_of(&core, &monster, &d(60)).round, 1);
    let trophies = core.monster_trophies().unwrap();
    assert_eq!((trophies.len(), trophies[0].slain_forever), (1, true));
    assert_eq!(monster_draws(&core).len(), 3);
    assert!(
        core.link_monster_item(monster, MonsterLinkKind::Goal, goal, 10)
            .is_err()
    );
}

#[test]
fn the_final_boss_can_replace_a_resting_monsters_next_round() {
    let (core, _dir) = core();
    let monster = core.create_monster("Sugar".into(), 10, DAY.into()).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, DAY);
    core.monster_tick(DAY.into()).unwrap();
    core.make_final_boss(monster.clone(), d(1)).unwrap();
    let boss = health_of(&core, &monster, &d(1));
    // The next normal round would have 13 (10 + a quarter, rounded up), so the boss has 65.
    assert_eq!(
        (boss.round, boss.final_boss, boss.max_health),
        (2, true, 65)
    );
}

#[test]
fn trashed_goals_do_not_hurt_a_monster() {
    let (core, _dir) = core();
    let monster = core
        .create_monster("Sugar".into(), 100, DAY.into())
        .unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, DAY);
    trash(&core, &goal);
    assert_eq!(health_of(&core, &monster, DAY).health, 100);
}

#[test]
fn with_no_treat_in_the_jar_a_win_pays_nothing_and_prizes_can_be_enjoyed() {
    let (core, _dir) = core();
    let monster = core.create_monster("Sugar".into(), 10, DAY.into()).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster.clone(), MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, DAY);
    assert_eq!(core.monster_tick(DAY.into()).unwrap()[0].prizes, 0);
    drop(core);

    let (core2, _dir2) = self::core();
    let core = core2;
    let treat = {
        core.add_treat("Movie".into()).unwrap();
        core.list_treats().unwrap()[0].id.clone()
    };
    let monster = core.create_monster("Sugar".into(), 10, DAY.into()).unwrap();
    core.set_monster_prize(monster.clone(), Some(treat))
        .unwrap();
    let goal = daily_habit(&core, "Walk");
    core.link_monster_item(monster, MonsterLinkKind::Goal, goal.clone(), 10)
        .unwrap();
    tick(&core, &goal, DAY);
    core.monster_tick(DAY.into()).unwrap();
    let draw = monster_draws(&core).remove(0);
    assert_eq!(draw.treat_title, "Movie");
    let unseen = |core: &GoalCore| {
        core.unseen_draws()
            .unwrap()
            .iter()
            .filter(|d| d.from_monster)
            .count()
    };
    assert_eq!(unseen(&core), 1);
    core.mark_draw_seen(draw.id.clone()).unwrap();
    assert_eq!(unseen(&core), 0);
    core.toggle_redeemed(draw.id.clone(), DAY.into()).unwrap();
    assert!(monster_draws(&core)[0].redeemed_date.is_some());
}

#[test]
fn monsters_survive_a_backup_and_bad_monster_dates_are_rejected() {
    let (core, dir) = core();
    let monster = core.create_monster("Sugar".into(), 40, DAY.into()).unwrap();
    let path = dir.path().join("b.sqlite").to_str().unwrap().to_string();
    core.export_backup(path.clone()).unwrap();
    core.delete_monster(monster.clone()).unwrap();
    core.restore_backup(path.clone()).unwrap();
    assert_eq!(health_of(&core, &monster, DAY).max_health, 40);

    let conn = Connection::open(&path).unwrap();
    conn.execute("UPDATE monster_rounds SET start_date = 'whenever'", [])
        .unwrap();
    drop(conn);
    assert_invalid_backup(core.restore_backup(path), "invalid dates");
}

#[test]
fn upgrading_from_schema_12_adds_monsters() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("v12.db").to_str().unwrap().to_string();
    {
        let mut conn = rusqlite::Connection::open(&path).unwrap();
        db::migrate_to(&mut conn, 12).unwrap();
        conn.execute_batch(
            "INSERT INTO goals (id, title, kind, created_date) VALUES ('g1', 'Old', 'yes_no', '2026-01-01');",
        )
        .unwrap();
    }
    let core = GoalCore::open(path).unwrap();
    assert!(core.list_monsters(DAY.into()).unwrap().is_empty());
    assert!(core.get_goal("g1".into()).is_ok());
}

// ---- XP, hero levels and the weekly boss ----

/// Makes `n` daily habits, so that ticking them all earns `n * 10` points in a day.
fn habits(core: &GoalCore, n: usize) -> Vec<String> {
    (0..n)
        .map(|i| daily_habit(core, &format!("Habit {i}")))
        .collect()
}

#[test]
fn xp_is_the_sum_of_every_days_points_and_levels_grow() {
    let (core, _dir) = core();
    let hero = core.hero().unwrap();
    assert_eq!((hero.xp, hero.level, hero.title.as_str()), (0, 1, "Rookie"));
    let ids = habits(&core, 1);
    // One habit is 10 points a day, so ten days is 100 XP: level 2.
    for n in 0..10 {
        tick(&core, &ids[0], &d(n));
        core.progress_tick(d(n)).unwrap();
    }
    let hero = core.hero().unwrap();
    assert_eq!((hero.xp, hero.level), (100, 2));
    assert_eq!((hero.xp_into_level, hero.xp_for_next), (0, 150));
}

#[test]
fn undoing_today_takes_todays_xp_back_but_old_days_stay() {
    let (core, _dir) = core();
    let ids = habits(&core, 1);
    tick(&core, &ids[0], &d(0));
    core.progress_tick(d(0)).unwrap();
    tick(&core, &ids[0], &d(1));
    core.progress_tick(d(1)).unwrap();
    assert_eq!(core.hero().unwrap().xp, 20);
    // Undo today.
    tick(&core, &ids[0], &d(1));
    core.progress_tick(d(1)).unwrap();
    assert_eq!(core.hero().unwrap().xp, 10);
}

#[test]
fn a_new_week_starts_with_a_boss_that_your_points_hurt() {
    let (core, _dir) = core();
    assert!(core.weekly_boss(DAY.into()).unwrap().is_none());
    let ids = habits(&core, 3);
    core.progress_tick(DAY.into()).unwrap();
    let boss = core.weekly_boss(DAY.into()).unwrap().unwrap();
    // DAY is a Thursday, so the week began on Monday the 2nd.
    assert_eq!(boss.week_start, "2026-11-02");
    assert_eq!((boss.health, boss.damage, boss.days_left), (100, 0, 4));
    for id in &ids {
        tick(&core, id, DAY);
    }
    assert!(core.progress_tick(DAY.into()).unwrap().is_empty());
    assert_eq!(core.weekly_boss(DAY.into()).unwrap().unwrap().damage, 30);
}

#[test]
fn beating_the_weekly_boss_wins_one_treat_once() {
    let (core, _dir) = core();
    core.add_treat("Movie night".into()).unwrap();
    let ids = habits(&core, 10);
    core.progress_tick(DAY.into()).unwrap();
    for id in &ids {
        tick(&core, id, DAY);
    }
    let events = core.progress_tick(DAY.into()).unwrap();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].prizes, 1);
    // Asking again does not pay twice.
    assert!(core.progress_tick(DAY.into()).unwrap().is_empty());
    let prizes: Vec<_> = core
        .list_draws()
        .unwrap()
        .into_iter()
        .filter(|d| d.goal_title.starts_with("Weekly boss: "))
        .collect();
    assert_eq!(prizes.len(), 1);
    assert_eq!(prizes[0].treat_title, "Movie night");
    core.toggle_redeemed(prizes[0].id.clone(), DAY.into())
        .unwrap();
    let boss = core.weekly_boss(DAY.into()).unwrap().unwrap();
    assert_eq!(boss.defeated_date.as_deref(), Some(DAY));
}

#[test]
fn next_weeks_boss_scales_with_your_recent_weeks() {
    let (core, _dir) = core();
    let ids = habits(&core, 20);
    core.progress_tick(DAY.into()).unwrap();
    for id in &ids {
        tick(&core, id, DAY);
    }
    core.progress_tick(DAY.into()).unwrap();
    // Monday the 9th: 80% of the 200 points last week, rounded up to a multiple of 10.
    core.progress_tick(d(4)).unwrap();
    let boss = core.weekly_boss(d(4)).unwrap().unwrap();
    assert_eq!((boss.week_start.as_str(), boss.health), ("2026-11-09", 160));
    let history = core.boss_history().unwrap();
    assert_eq!(history.len(), 2);
    assert_eq!(history[0].week_start, "2026-11-09");
    assert!(history[1].defeated_date.is_some());
}

#[test]
fn a_boss_prize_is_skipped_when_the_jar_is_empty() {
    let (core, _dir) = core();
    let ids = habits(&core, 10);
    core.progress_tick(DAY.into()).unwrap();
    for id in &ids {
        tick(&core, id, DAY);
    }
    let events = core.progress_tick(DAY.into()).unwrap();
    assert_eq!((events.len(), events[0].prizes), (1, 0));
}

// ---- Calendar and time blocking ----

fn event(title: &str, date: &str, start: u32, minutes: u32) -> NewEvent {
    NewEvent {
        title: title.into(),
        date: date.into(),
        start_minutes: start,
        minutes,
        repeat: EventRepeat::None,
        weekdays: None,
        until_date: None,
        remind_minutes: None,
    }
}

fn plain_task(core: &GoalCore, title: &str, due: Option<&str>) -> String {
    core.create_task(
        NewTask {
            title: title.into(),
            notes: String::new(),
            due: due.map(String::from),
            priority: Priority::Med,
            tag: None,
            repeat: None,
            items: vec![],
            goal_id: None,
        },
        DAY.into(),
    )
    .unwrap()
}

#[test]
fn an_event_shows_on_its_day_only() {
    let (core, _dir) = core();
    core.create_event(event("Dentist", DAY, 9 * 60, 45))
        .unwrap();
    let blocks = core.calendar_day(DAY.into()).unwrap();
    assert_eq!(blocks.len(), 1);
    assert_eq!(blocks[0].kind, CalendarKind::Event);
    assert_eq!(
        (blocks[0].start_minutes, blocks[0].minutes),
        (Some(540), 45)
    );
    assert!(core.calendar_day(d(1)).unwrap().is_empty());
    assert!(core.calendar_day(d(0)).unwrap().len() == 1);
}

#[test]
fn events_are_checked() {
    let (core, _dir) = core();
    assert!(core.create_event(event("  ", DAY, 60, 30)).is_err());
    assert!(core.create_event(event("x", DAY, 60, 4)).is_err());
    // Must end before midnight.
    assert!(
        core.create_event(event("x", DAY, 23 * 60 + 50, 30))
            .is_err()
    );
    assert!(core.create_event(event("x", "2026-13-40", 60, 30)).is_err());
    let mut weekly = event("x", DAY, 60, 30);
    weekly.repeat = EventRepeat::Weekly;
    weekly.weekdays = Some(0);
    assert!(core.create_event(weekly).is_err());
}

#[test]
fn repeating_events_follow_their_rule() {
    let (core, _dir) = core();
    // DAY is a Thursday.
    let mut daily = event("Stand-up", DAY, 600, 15);
    daily.repeat = EventRepeat::Daily;
    core.create_event(daily).unwrap();
    let mut weekly = event("Class", DAY, 700, 60);
    weekly.repeat = EventRepeat::Weekly;
    core.create_event(weekly).unwrap();
    let mut mwf = event("Gym", DAY, 800, 60);
    mwf.repeat = EventRepeat::Weekly;
    mwf.weekdays = Some(1 | 4 | 16); // Monday, Wednesday, Friday
    core.create_event(mwf).unwrap();
    let titles = |date: String| -> Vec<String> {
        core.calendar_day(date)
            .unwrap()
            .into_iter()
            .map(|b| b.title)
            .collect()
    };
    // Before the start nothing shows.
    assert!(titles(d(0).replace("05", "04")).is_empty());
    assert_eq!(titles(DAY.into()), ["Stand-up", "Class"]);
    // Friday: stand-up and gym.
    assert_eq!(titles(d(1)), ["Stand-up", "Gym"]);
    // Next Thursday: stand-up and class again.
    assert_eq!(titles(d(7)), ["Stand-up", "Class"]);
}

#[test]
fn tasks_show_on_their_due_day_with_or_without_a_time() {
    let (core, _dir) = core();
    let timed = plain_task(&core, "Call mum", Some(DAY));
    let untimed = plain_task(&core, "Buy milk", Some(DAY));
    plain_task(&core, "Someday", None);
    core.set_task_time(timed.clone(), Some(14 * 60), Some(20))
        .unwrap();
    let blocks = core.calendar_day(DAY.into()).unwrap();
    assert_eq!(blocks.len(), 2);
    assert_eq!(blocks[0].id, timed);
    assert_eq!(
        (blocks[0].start_minutes, blocks[0].minutes),
        (Some(840), 20)
    );
    // Untimed ones follow, as 30 minute "anytime" blocks.
    assert_eq!(blocks[1].id, untimed);
    assert_eq!((blocks[1].start_minutes, blocks[1].minutes), (None, 30));
    // Done tasks stay, marked done.
    core.toggle_task(untimed, DAY.into()).unwrap();
    assert!(core.calendar_day(DAY.into()).unwrap()[1].done);
}

#[test]
fn a_task_time_needs_a_due_day_and_clears_with_it() {
    let (core, _dir) = core();
    let none = plain_task(&core, "No day", None);
    assert!(core.set_task_time(none, Some(60), None).is_err());
    let task = plain_task(&core, "With day", Some(DAY));
    core.set_task_time(task.clone(), Some(60), Some(45))
        .unwrap();
    let shown = core.get_task(task.clone(), DAY.into()).unwrap();
    assert_eq!((shown.due_time, shown.duration), (Some(60), Some(45)));
    // Removing the due day removes the start time.
    let mut edit = TaskEdit {
        title: shown.title.clone(),
        notes: String::new(),
        due: None,
        priority: shown.priority,
        tag: None,
        repeat: None,
        goal_id: None,
    };
    core.update_task(task.clone(), edit.clone()).unwrap();
    assert_eq!(
        core.get_task(task.clone(), DAY.into()).unwrap().due_time,
        None
    );
    edit.due = Some(DAY.into());
    core.update_task(task.clone(), edit).unwrap();
    assert_eq!(core.get_task(task, DAY.into()).unwrap().due_time, None);
}

#[test]
fn goals_with_a_block_show_at_that_time_and_others_anytime() {
    let (core, _dir) = core();
    let run = daily_habit(&core, "Run");
    let read = daily_habit(&core, "Read");
    core.set_goal_block(run.clone(), Some(7 * 60), Some(40))
        .unwrap();
    assert_eq!(
        core.goal_block(run.clone()).unwrap(),
        GoalBlock {
            start_minutes: Some(420),
            minutes: 40
        }
    );
    let blocks = core.calendar_day(DAY.into()).unwrap();
    assert_eq!(blocks.len(), 2);
    assert_eq!(
        (blocks[0].id.as_str(), blocks[0].start_minutes),
        (run.as_str(), Some(420))
    );
    assert_eq!(
        (blocks[1].id.as_str(), blocks[1].start_minutes),
        (read.as_str(), None)
    );
    // A block needs a start time, and clearing works.
    assert!(core.set_goal_block(read, None, Some(30)).is_err());
    core.set_goal_block(run.clone(), None, None).unwrap();
    assert_eq!(core.goal_block(run).unwrap().start_minutes, None);
}

#[test]
fn calendar_items_never_touch_the_score_or_xp() {
    let (core, _dir) = core();
    core.create_event(event("Party", DAY, 600, 60)).unwrap();
    let goal = daily_habit(&core, "Walk");
    core.set_goal_block(goal, Some(480), Some(30)).unwrap();
    let before = core.day_score(DAY.into()).unwrap();
    assert_eq!(before.possible, 10);
    core.progress_tick(DAY.into()).unwrap();
    assert_eq!(core.hero().unwrap().xp, 0);
}

#[test]
fn deleting_an_event_removes_it() {
    let (core, _dir) = core();
    let id = core.create_event(event("Temp", DAY, 60, 30)).unwrap();
    assert!(core.get_event(id.clone()).unwrap().is_some());
    core.delete_event(id.clone()).unwrap();
    assert!(core.get_event(id).unwrap().is_none());
    assert!(core.calendar_day(DAY.into()).unwrap().is_empty());
}

// ---- 0.10.0: emptying the trash ----

#[test]
fn emptying_the_goal_trash_removes_goals_with_all_their_history() {
    let (core, _dir) = core();
    core.add_treat("Movie night".into()).unwrap();
    let goal = daily_habit(&core, "Run");
    // A log (which wins a treat), a reminder, a monster link and a linked task all hang off the goal.
    tick(&core, &goal, DAY);
    core.set_goal_reminder(goal.clone(), Some(7 * 60)).unwrap();
    let monster = core.create_monster("Couch".into(), 100, DAY.into()).unwrap();
    core.link_monster_item(monster, MonsterLinkKind::Goal, goal.clone(), 10).unwrap();
    add_task(&core, NewTask { goal_id: Some(goal.clone()), ..new_task("Buy shoes") });
    let keep = daily_habit(&core, "Read");
    core.trash_goal(goal, DAY.into()).unwrap();
    assert_eq!(core.list_goal_trash(DAY.into()).unwrap().len(), 1);
    assert_eq!(core.empty_goal_trash().unwrap(), 1);
    assert!(core.list_goal_trash(DAY.into()).unwrap().is_empty());
    // Other goals are untouched, and a second call has nothing left to remove.
    assert_eq!(titles(&core, DAY), ["Read"]);
    assert_eq!(core.empty_goal_trash().unwrap(), 0);
    assert!(core.get_goal(keep).is_ok());
}

#[test]
fn emptying_the_task_trash_removes_only_trashed_tasks_even_with_repeats() {
    let (core, _dir) = core();
    let repeating = add_task(
        &core,
        NewTask { due: Some(DAY.into()), repeat: Some(Repeat::Daily), ..new_task("Water plants") },
    );
    // Finishing a repeating task makes its next copy, so the two point at each other.
    core.toggle_task(repeating.clone(), DAY.into()).unwrap();
    let steps = add_task(&core, NewTask { items: vec!["a".into(), "b".into()], ..new_task("With steps") });
    let kept = add_task(&core, new_task("Keep me"));
    core.delete_task(repeating, DAY.into()).unwrap();
    core.delete_task(steps, DAY.into()).unwrap();
    assert_eq!(core.empty_task_trash().unwrap(), 2);
    assert!(core.get_task(kept, DAY.into()).is_ok());
    assert_eq!(core.empty_task_trash().unwrap(), 0);
}

#[test]
fn a_repeating_event_can_end_and_skip_a_day() {
    let (core, _dir) = core();
    let mut stand_up = event("Stand-up", DAY, 600, 15);
    stand_up.repeat = EventRepeat::Daily;
    stand_up.until_date = Some(d(2));
    let id = core.create_event(stand_up).unwrap();
    let count = |date: String| core.calendar_day(date).unwrap().len();
    assert_eq!((count(DAY.into()), count(d(1)), count(d(2)), count(d(3))), (1, 1, 1, 0));
    core.skip_event_day(id.clone(), d(1)).unwrap();
    assert_eq!((count(DAY.into()), count(d(1)), count(d(2))), (1, 0, 1));
    // Skipping twice is harmless, and a one-off event has nothing to skip.
    core.skip_event_day(id, d(1)).unwrap();
    let once = core.create_event(event("Once", DAY, 60, 30)).unwrap();
    assert!(core.skip_event_day(once, DAY.into()).is_err());
}

#[test]
fn event_end_dates_are_checked() {
    let (core, _dir) = core();
    let mut bad = event("x", DAY, 60, 30);
    bad.until_date = Some(DAY.into());
    // A one-off event has nothing to end.
    assert!(core.create_event(bad.clone()).is_err());
    bad.repeat = EventRepeat::Daily;
    bad.until_date = Some("2026-11-01".into());
    assert!(core.create_event(bad).is_err());
}

#[test]
fn event_reminders_list_the_coming_days_with_a_reminder() {
    let (core, _dir) = core();
    let mut daily = event("Meds", DAY, 8 * 60, 5);
    daily.repeat = EventRepeat::Daily;
    daily.remind_minutes = Some(10);
    core.create_event(daily).unwrap();
    core.create_event(event("No reminder", DAY, 9 * 60, 30)).unwrap();
    let list = core.event_reminders(DAY.into(), 3).unwrap();
    assert_eq!(list.len(), 3);
    assert_eq!((list[0].date.as_str(), list[0].start_minutes, list[0].remind_minutes), (DAY, 480, 10));
    assert_eq!(list[2].date, d(2));
}

#[test]
fn the_month_overview_counts_blocks_per_day() {
    let (core, _dir) = core();
    core.create_event(event("One", &d(1), 60, 30)).unwrap();
    let marks = core.calendar_month(DAY.into(), 3).unwrap();
    assert_eq!(marks.iter().map(|m| m.count).collect::<Vec<_>>(), [0, 1, 0]);
}

// ---- Day history ----

#[test]
fn day_history_lists_recorded_days_and_what_happened() {
    let (core, _dir) = core();
    let ids = habits(&core, 2);
    tick(&core, &ids[0], DAY);
    let task = add_task(&core, NewTask { due: Some(DAY.into()), ..new_task("Email") });
    core.toggle_task(task, DAY.into()).unwrap();
    core.progress_tick(DAY.into()).unwrap();
    let rows = core.day_rows(DAY.into(), 7).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!((rows[0].earned, rows[0].possible), (20, 30));
    assert_eq!(rows[0].level, DayLevel::Blaze);
    let detail = core.day_detail(DAY.into()).unwrap();
    assert_eq!(detail.tasks_done, ["Email"]);
    assert_eq!(detail.goals_logged, ["Habit 0"]);
    // A day before anything was recorded has no row, and says nothing else either.
    let empty = core.day_detail(d(3)).unwrap();
    assert!(empty.row.is_none() && empty.tasks_done.is_empty());
}

#[test]
fn blaze_streaks_count_days_in_a_row_and_forgive_a_day_in_progress() {
    let (core, _dir) = core();
    let ids = habits(&core, 1);
    // One habit: finishing it is 100%, a Titan day, which also counts as Blaze or better.
    for n in [0, 1, 2, 4] {
        tick(&core, &ids[0], &d(n));
        core.progress_tick(d(n)).unwrap();
    }
    let on_day_two = core.day_records(d(2)).unwrap();
    assert_eq!((on_day_two.blaze_streak, on_day_two.longest_blaze_streak), (3, 3));
    // Day 3 was missed, so the streak restarted; day 4 is done.
    let on_day_four = core.day_records(d(4)).unwrap();
    assert_eq!((on_day_four.blaze_streak, on_day_four.longest_blaze_streak, on_day_four.titan_days), (1, 3, 4));
    // Day 5 is still in progress, so it does not break the streak yet.
    assert_eq!(core.day_records(d(5)).unwrap().blaze_streak, 1);
    // Day 6 had nothing done by its end of day 5, so the streak is over.
    assert_eq!(core.day_records(d(6)).unwrap().blaze_streak, 0);
    assert_eq!(on_day_four.best_day_points, 10);
}

// ---- Quick add: times and lengths ----

fn quick(line: &str) -> ParsedTask {
    let (core, _dir) = core();
    core.parse_quick(line.into(), DAY.into()).unwrap()
}

#[test]
fn quick_add_reads_a_start_time_and_length() {
    let p = quick("Dentist friday 3pm 45m !high");
    assert_eq!(p.title, "Dentist");
    assert_eq!((p.time, p.minutes), (Some(15 * 60), Some(45)));
    assert_eq!(p.due.as_deref(), Some("2026-11-06"));
    let p = quick("Standup tomorrow at 9:30am 15m");
    assert_eq!((p.title.as_str(), p.time, p.minutes), ("Standup", Some(570), Some(15)));
    let p = quick("Train today 18:05 1.5h");
    assert_eq!((p.time, p.minutes), (Some(18 * 60 + 5), Some(90)));
    let p = quick("Call mum tomorrow 12am");
    assert_eq!(p.time, Some(0));
}

#[test]
fn quick_add_leaves_ordinary_numbers_in_the_title() {
    // No day, so a time is not read; a bare number is never a time or a length.
    let p = quick("Read 3pm 20m");
    assert_eq!((p.time, p.minutes), (None, None));
    assert_eq!(p.title, "Read 3pm 20m");
    let p = quick("Buy 2026 stamps tomorrow");
    assert_eq!((p.time, p.minutes), (None, None));
    assert_eq!(p.title, "Buy 2026 stamps");
    // A length without a start time stays in the title, because "45m" could mean anything.
    let p = quick("Plan week tomorrow 45m");
    assert_eq!((p.time, p.minutes), (None, None));
    assert_eq!(p.title, "Plan week 45m");
    // Impossible clock times stay as text.
    let p = quick("Meet tomorrow 25:00");
    assert_eq!(p.time, None);
    assert_eq!(p.title, "Meet 25:00");
}

// ---- Task templates ----

#[test]
fn a_template_makes_new_tasks_with_the_same_steps() {
    let (core, _dir) = core();
    let source = add_task(
        &core,
        NewTask {
            notes: "Bring the form".into(),
            priority: Priority::High,
            tag: Some("home".into()),
            items: vec!["Pack bag".into(), "Lock door".into()],
            ..new_task("Leave the house")
        },
    );
    core.set_task_time(source.clone(), None, Some(20)).unwrap();
    let template = core.save_task_template(source, "Leaving".into(), DAY.into()).unwrap();
    let list = core.list_task_templates().unwrap();
    assert_eq!((list[0].name.as_str(), list[0].steps, list[0].minutes), ("Leaving", 2, Some(20)));

    let made = core.task_from_template(template.clone(), Some(d(1)), DAY.into()).unwrap();
    let task = core.get_task(made.clone(), DAY.into()).unwrap();
    assert_eq!((task.title.as_str(), task.priority, task.tag.as_deref()), ("Leave the house", Priority::High, Some("home")));
    assert_eq!((task.due.as_deref(), task.duration, task.items_total), (Some(d(1).as_str()), Some(20), 2));
    // A copy is its own task, with steps that start unticked.
    assert_eq!(task.items_done, 0);
    core.delete_task_template(template.clone()).unwrap();
    assert!(core.task_from_template(template, None, DAY.into()).is_err());
}

#[test]
fn template_names_are_checked() {
    let (core, _dir) = core();
    let id = add_task(&core, new_task("A"));
    assert!(core.save_task_template(id.clone(), "  ".into(), DAY.into()).is_err());
    core.save_task_template(id.clone(), "Mine".into(), DAY.into()).unwrap();
    assert!(core.save_task_template(id, "mine".into(), DAY.into()).is_err());
}

#[test]
fn quick_add_saves_the_start_time_and_length() {
    let (core, _dir) = core();
    let id = core.quick_add_task("Dentist friday 3pm 45m".into(), DAY.into()).unwrap();
    let task = core.get_task(id, DAY.into()).unwrap();
    assert_eq!((task.due_time, task.duration), (Some(15 * 60), Some(45)));
}
