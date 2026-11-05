//! Calendar periods. Dates are plain local dates supplied by the app, so results
//! never depend on the clock or time zone of the machine running this code.

use chrono::{Datelike, Days, Months, NaiveDate};

use crate::CoreError;
use crate::model::Period;

pub fn parse_date(value: &str) -> Result<NaiveDate, CoreError> {
    NaiveDate::parse_from_str(value, "%Y-%m-%d")
        .ok()
        // Round-tripping rejects unpadded forms, which would break text ordering in SQL.
        .filter(|date| date.to_string() == value)
        .ok_or_else(|| CoreError::Invalid {
            reason: format!("invalid date: {value}"),
        })
}

/// The first day of the period containing `date`. Weeks start on Monday.
/// True if a daily habit with this weekday bitmask (Monday = 1) is due on `date`.
/// No mask means every day.
pub fn is_scheduled(weekdays: Option<u8>, date: NaiveDate) -> bool {
    weekdays.is_none_or(|mask| mask & (1 << date.weekday().num_days_from_monday()) != 0)
}

#[cfg(test)]
pub fn period_start(date: NaiveDate, period: Period) -> NaiveDate {
    period_start_ws(date, period, 1)
}

/// Like `period_start`, but weeks begin on `week_start` (Monday = 1 ... Sunday = 7).
/// Periods are worked out when read, so changing the setting never rewrites history.
pub fn period_start_ws(date: NaiveDate, period: Period, week_start: u8) -> NaiveDate {
    match period {
        Period::Day => date,
        Period::Week => {
            // How many days have passed since the week began (0 to 6).
            let since = (date.weekday().number_from_monday() + 7 - u32::from(week_start)) % 7;
            date - Days::new(u64::from(since))
        }
        Period::Month => date.with_day(1).expect("every month has a first day"),
    }
}

/// Weekday numbers the setting accepts: Monday, Saturday or Sunday.
pub fn valid_week_start(value: u8) -> bool {
    matches!(value, 1 | 6 | 7)
}

const WEEK_START_KEY: &str = "week_start";

/// The first day of the week the user chose. Defaults to Monday.
pub fn week_start(conn: &rusqlite::Connection) -> u8 {
    conn.query_row(
        "SELECT value FROM meta WHERE key = ?1",
        [WEEK_START_KEY],
        |r| r.get::<_, String>(0),
    )
    .ok()
    .and_then(|v| v.parse::<u8>().ok())
    .filter(|v| valid_week_start(*v))
    .unwrap_or(1)
}

pub fn set_week_start(conn: &rusqlite::Connection, value: u8) -> Result<(), CoreError> {
    if !valid_week_start(value) {
        return Err(CoreError::Invalid {
            reason: "Weeks can start on Monday, Saturday or Sunday".into(),
        });
    }
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2)
         ON CONFLICT (key) DO UPDATE SET value = excluded.value",
        rusqlite::params![WEEK_START_KEY, value.to_string()],
    )?;
    Ok(())
}

pub fn next_start(start: NaiveDate, period: Period) -> NaiveDate {
    match period {
        Period::Day => start + Days::new(1),
        Period::Week => start + Days::new(7),
        Period::Month => start + Months::new(1),
    }
}

pub fn previous_start(start: NaiveDate, period: Period) -> NaiveDate {
    match period {
        Period::Day => start - Days::new(1),
        Period::Week => start - Days::new(7),
        Period::Month => start - Months::new(1),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(s: &str) -> NaiveDate {
        parse_date(s).unwrap()
    }

    #[test]
    fn rejects_malformed_and_impossible_dates() {
        for bad in [
            "2026-2-01",
            "2026-02-30",
            "2025-02-29",
            "today",
            "",
            "2026-11-05T00:00",
        ] {
            assert!(parse_date(bad).is_err(), "{bad}");
        }
        assert!(parse_date("2024-02-29").is_ok());
    }

    #[test]
    fn weeks_start_on_monday_across_year_boundaries() {
        // 2026-01-01 is a Thursday, so its week begins in the previous year.
        assert_eq!(period_start(d("2026-01-01"), Period::Week), d("2025-12-29"));
        assert_eq!(period_start(d("2026-11-02"), Period::Week), d("2026-11-02")); // Monday
        assert_eq!(period_start(d("2026-11-08"), Period::Week), d("2026-11-02")); // Sunday
    }

    #[test]
    fn weeks_can_start_on_sunday_or_saturday() {
        // 2026-11-05 is a Thursday.
        assert_eq!(
            period_start_ws(d("2026-11-05"), Period::Week, 7),
            d("2026-11-01")
        );
        assert_eq!(
            period_start_ws(d("2026-11-01"), Period::Week, 7),
            d("2026-11-01")
        );
        assert_eq!(
            period_start_ws(d("2026-11-07"), Period::Week, 7),
            d("2026-11-01")
        );
        assert_eq!(
            period_start_ws(d("2026-11-05"), Period::Week, 6),
            d("2026-10-31")
        );
        assert_eq!(
            period_start_ws(d("2026-11-05"), Period::Week, 1),
            d("2026-11-02")
        );
    }

    #[test]
    fn months_handle_leap_years_and_year_ends() {
        assert_eq!(
            period_start(d("2024-02-29"), Period::Month),
            d("2024-02-01")
        );
        assert_eq!(next_start(d("2024-02-01"), Period::Month), d("2024-03-01"));
        assert_eq!(next_start(d("2026-12-01"), Period::Month), d("2027-01-01"));
        assert_eq!(
            previous_start(d("2027-01-01"), Period::Month),
            d("2026-12-01")
        );
    }

    #[test]
    fn days_step_over_leap_day() {
        assert_eq!(next_start(d("2024-02-28"), Period::Day), d("2024-02-29"));
        assert_eq!(next_start(d("2024-02-29"), Period::Day), d("2024-03-01"));
    }
}
