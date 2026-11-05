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
