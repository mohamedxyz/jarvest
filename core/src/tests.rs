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
    core.add_milestone(id.clone(), "C".into()).unwrap();
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
}
