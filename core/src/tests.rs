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
