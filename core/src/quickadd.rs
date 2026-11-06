//! Reads a due date, a `!priority` and a `#tag` out of a quick-add line, such as
//! `Essay tomorrow !high #uni`. Every part must be its own word (spaces around it).
//! English only. `today` comes from the caller, so results never depend on the clock.

use chrono::{Datelike, Days, NaiveDate};

use crate::CoreError;
use crate::dates::parse_date;
use crate::model::{ParsedTask, Priority};

const MONTHS: [&str; 12] = [
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
];

/// Days after Sunday (0) for a weekday name.
fn weekday_number(word: &str) -> Option<u32> {
    Some(match word {
        "sunday" | "sun" => 0,
        "monday" | "mon" => 1,
        "tuesday" | "tues" | "tue" => 2,
        "wednesday" | "wed" => 3,
        "thursday" | "thurs" | "thur" | "thu" => 4,
        "friday" | "fri" => 5,
        "saturday" | "sat" => 6,
        _ => None?,
    })
}

fn month_number(word: &str) -> Option<u32> {
    let lower = word.to_lowercase();
    if lower.len() < 3 || !lower.chars().all(|c| c.is_ascii_alphabetic()) {
        return None;
    }
    MONTHS
        .iter()
        .position(|m| lower.starts_with(m))
        .map(|i| i as u32 + 1)
}

fn number(word: &str, max_digits: usize) -> Option<u32> {
    if word.is_empty() || word.len() > max_digits || !word.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    word.parse().ok()
}

/// A day and month with no year: this year, or next year if already past.
fn day_month(today: NaiveDate, day: u32, month: u32) -> Option<NaiveDate> {
    let this_year = NaiveDate::from_ymd_opt(today.year(), month, day)?;
    if this_year >= today {
        Some(this_year)
    } else {
        NaiveDate::from_ymd_opt(today.year() + 1, month, day)
    }
}

fn weekday_date(today: NaiveDate, target: u32) -> NaiveDate {
    let now = today.weekday().num_days_from_sunday();
    today + Days::new(u64::from((target + 7 - now) % 7))
}

/// Tries one date rule at token `i`. Returns the date and how many words it used.
type Rule = fn(&[&str], usize, NaiveDate) -> Option<(NaiveDate, usize)>;

fn rule_today(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    matches!(w[i].to_lowercase().as_str(), "today" | "tod").then_some((today, 1))
}

fn rule_tomorrow(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    matches!(w[i].to_lowercase().as_str(), "tomorrow" | "tmr" | "tmrw")
        .then(|| (today + Days::new(1), 1))
}

fn rule_in_days(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    if !w[i].eq_ignore_ascii_case("in") || i + 2 >= w.len() {
        return None;
    }
    let count = number(w[i + 1], 3)?;
    let unit = w[i + 2].to_lowercase();
    let per = match unit.as_str() {
        "day" | "days" => 1,
        "week" | "weeks" => 7,
        _ => return None,
    };
    Some((today + Days::new(u64::from(count * per)), 3))
}

fn rule_next_week(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    if !w[i].eq_ignore_ascii_case("next")
        || i + 1 >= w.len()
        || !w[i + 1].eq_ignore_ascii_case("week")
    {
        return None;
    }
    // The coming Monday, never today.
    let now = today.weekday().num_days_from_sunday();
    let ahead = (8 - now) % 7;
    Some((
        today + Days::new(u64::from(if ahead == 0 { 7 } else { ahead })),
        2,
    ))
}

fn rule_weekday(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    // An optional "on" before the name is used up with it.
    let (start, used) = if w[i].eq_ignore_ascii_case("on") && i + 1 < w.len() {
        (i + 1, 2)
    } else {
        (i, 1)
    };
    let word = w[start];
    // Capitals such as MON are most likely an abbreviation, not a day.
    if word.len() >= 3 && word.chars().all(|c| c.is_ascii_uppercase()) {
        return None;
    }
    let target = weekday_number(&word.to_lowercase())?;
    Some((weekday_date(today, target), used))
}

fn rule_iso(w: &[&str], i: usize, _today: NaiveDate) -> Option<(NaiveDate, usize)> {
    let parts: Vec<&str> = w[i].split('-').collect();
    if parts.len() != 3 {
        return None;
    }
    let year = number(parts[0], 4).filter(|_| parts[0].len() == 4)?;
    let month = number(parts[1], 2)?;
    let day = number(parts[2], 2)?;
    Some((NaiveDate::from_ymd_opt(year as i32, month, day)?, 1))
}

fn rule_day_month_name(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    if i + 1 >= w.len() {
        return None;
    }
    let day = number(w[i], 2)?;
    let month = month_number(w[i + 1])?;
    Some((day_month(today, day, month)?, 2))
}

fn rule_month_name_day(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    if i + 1 >= w.len() {
        return None;
    }
    let month = month_number(w[i])?;
    let day = number(w[i + 1], 2)?;
    Some((day_month(today, day, month)?, 2))
}

/// In order of priority: the first rule that matches anywhere in the line wins.
const RULES: [Rule; 9] = [
    rule_today,
    rule_tomorrow,
    rule_in_days,
    rule_next_week,
    rule_weekday,
    rule_iso,
    rule_day_month_name,
    rule_month_name_day,
    // Slash dates only count as the last word, so "1/2 cup" in the middle stays text.
    rule_slash_date_last,
];

fn rule_slash_date_last(w: &[&str], i: usize, today: NaiveDate) -> Option<(NaiveDate, usize)> {
    if i + 1 != w.len() {
        return None;
    }
    let (day, month) = w[i].split_once('/')?;
    let (day, month) = (number(day, 2)?, number(month, 2)?);
    Some((day_month(today, day, month)?, 1))
}

fn priority_of(word: &str) -> Option<Priority> {
    Some(match word.to_lowercase().as_str() {
        "high" | "h" | "3" => Priority::High,
        "medium" | "med" | "m" | "2" => Priority::Med,
        "low" | "l" | "1" => Priority::Low,
        _ => return None,
    })
}

fn valid_tag(tag: &str) -> bool {
    let len = tag.chars().count();
    (1..=40).contains(&len)
        && tag
            .chars()
            .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
}

pub fn parse_quick(raw: &str, today: &str) -> Result<ParsedTask, CoreError> {
    let today = parse_date(today)?;
    let words: Vec<&str> = raw.split_whitespace().collect();
    let mut used = vec![false; words.len()];
    let mut due: Option<NaiveDate> = None;

    'rules: for rule in RULES {
        for i in 0..words.len() {
            if used[i] {
                continue;
            }
            // Only look at words that are still free, so a rule can't reuse one.
            let free: Vec<&str> = words
                .iter()
                .enumerate()
                .map(|(j, w)| if used[j] { "" } else { *w })
                .collect();
            if free[i].is_empty() {
                continue;
            }
            if let Some((date, count)) = rule(&free, i, today)
                && (i..i + count).all(|j| !used[j])
            {
                due = Some(date);
                for flag in &mut used[i..i + count] {
                    *flag = true;
                }
                break 'rules;
            }
        }
    }

    let mut priority = None;
    let mut tag = None;
    let mut time = None;
    let mut minutes = None;
    for (i, word) in words.iter().enumerate() {
        if used[i] {
            continue;
        }
        if time.is_none()
            && due.is_some()
            && let Some(t) = clock_time(word)
        {
            time = Some(t);
            used[i] = true;
            // "at 3pm": the little word is part of the time.
            if i > 0 && !used[i - 1] && words[i - 1].eq_ignore_ascii_case("at") {
                used[i - 1] = true;
            }
            continue;
        }

        if priority.is_none()
            && let Some(p) = word.strip_prefix('!').and_then(priority_of)
        {
            priority = Some(p);
            used[i] = true;
        } else if tag.is_none()
            && let Some(t) = word.strip_prefix('#').filter(|t| valid_tag(t))
        {
            tag = Some(t.to_string());
            used[i] = true;
        }
    }

    // A length such as "45m" is only read together with a start time. Alone it could be anything.
    if time.is_some() {
        for (i, word) in words.iter().enumerate() {
            if !used[i]
                && let Some(m) = length_of(word)
            {
                minutes = Some(m);
                used[i] = true;
                break;
            }
        }
    }

    let title = words
        .iter()
        .enumerate()
        .filter(|(i, _)| !used[*i])
        .map(|(_, w)| *w)
        .collect::<Vec<_>>()
        .join(" ");
    Ok(ParsedTask {
        // A line that was only tokens keeps its text, so the task is not nameless.
        title: if title.is_empty() {
            raw.trim().to_string()
        } else {
            title
        },
        due: due.map(|d| d.to_string()),
        priority,
        tag,
        time,
        minutes,
    })
}

/// `3pm`, `3:30pm`, `11am` or 24-hour `15:30`, as minutes after midnight.
fn clock_time(word: &str) -> Option<u32> {
    let lower = word.to_lowercase();
    let (body, meridiem) = match lower.strip_suffix("am") {
        Some(b) => (b, Some(false)),
        None => match lower.strip_suffix("pm") {
            Some(b) => (b, Some(true)),
            None => (lower.as_str(), None),
        },
    };
    let (hours, mins) = match body.split_once(':') {
        Some((h, m)) => (number(h, 2)?, if m.len() == 2 { number(m, 2)? } else { return None }),
        // A bare number is only a time with am or pm, so "3" and "2026" stay in the title.
        None if meridiem.is_some() => (number(body, 2)?, 0),
        None => return None,
    };
    if mins > 59 {
        return None;
    }
    let hours = match meridiem {
        Some(pm) => {
            if !(1..=12).contains(&hours) {
                return None;
            }
            (hours % 12) + if pm { 12 } else { 0 }
        }
        None if hours <= 23 => hours,
        None => return None,
    };
    Some(hours * 60 + mins)
}

/// `45m`, `45min`, `2h` or `1.5h`, as minutes (5 to 1440).
fn length_of(word: &str) -> Option<u32> {
    let lower = word.to_lowercase();
    let total = if let Some(n) = lower.strip_suffix("min").or_else(|| lower.strip_suffix('m')) {
        number(n, 4)?
    } else if let Some(n) = lower.strip_suffix('h') {
        match n.split_once('.') {
            Some((whole, frac)) if frac.len() == 1 => number(whole, 2)? * 60 + number(frac, 1)? * 6,
            Some(_) => return None,
            None => number(n, 2)? * 60,
        }
    } else {
        return None;
    };
    (5..=1440).contains(&total).then_some(total)
}
