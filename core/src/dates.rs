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
pub fn period_start(date: NaiveDate, period: Period) -> NaiveDate {
    match period {
        Period::Day => date,
        Period::Week => date - Days::new(u64::from(date.weekday().num_days_from_monday())),
        Period::Month => date.with_day(1).expect("every month has a first day"),
    }
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
