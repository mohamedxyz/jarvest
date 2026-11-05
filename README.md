# Jarvest

A personal goals and habits app for Android. The rules, statistics, rewards, and
storage are written in Rust, and the screens are written in Kotlin with Jetpack
Compose. All data stays on the phone.

See [PLAN.md](PLAN.md) for the design and the exact rules.

## Features

- **Three goal types:** habits (such as 3 times a week), numeric goals (such as
  5 km a day or 300 pages in total), and one-off yes/no goals
- **Organizing:** categories, deadlines, ordered milestones, and an archive
- **Today screen and home screen widget** for logging in one tap
- **Stats:** activity heatmap, completion rates, and streaks
- **Reward jar:** add your own treats, then draw them at random when you hit targets
- **Backup:** everything saves to a single SQLite file that you can restore later

## Project layout

| Path | What it is |
|---|---|
| `core/` | The Rust library. Start with `src/lib.rs`, which lists everything the app can call. |
| `core/src/sql/` | The database structure |
| `android/app/src/main/java/dev/goaltracker/app/` | The Kotlin screens and widget |
| `android/app/build.gradle.kts` | The Android build, including the steps that compile the Rust code |
| `scripts/` | Toolchain setup and signing key creation |

Kotlin calls Rust through code that UniFFI generates during the build. You never
edit that code. When you add a `#[uniffi::export]` method in Rust, it becomes
available in Kotlin after the next build.

## First-time setup

You need Linux, Android Studio or the Android SDK command-line tools, and Java.

```bash
scripts/setup-toolchain.sh
```

This installs the pinned Rust version, Android's Rust targets, `cargo-ndk`, and
the Android NDK. It doesn't need `sudo` or change your shell config.

## Run the tests

The rules can be tested on your computer without a phone:

```bash
cd core
~/.cargo/bin/cargo test
```

## Run the app on your phone

**1. Turn on developer options (once).** Open **Settings > About phone** and tap
**Build number** seven times. The exact menu names vary between phone brands.

**2. Turn on USB debugging (once).** In **Settings > Developer options**, turn on
**USB debugging**.

**3. Connect your phone with USB.** Approve the prompt on the phone, then check
that it's listed as `device`:

```bash
adb devices
```

If it's listed as `unauthorized`, look for the prompt on your phone. If it's listed
with `no permissions`, your computer needs Android udev rules. On Arch, install the
`android-udev` package, then reconnect the phone.

**4. Build and install:**

```bash
cd android
./gradlew installDebug
```

The first build compiles SQLite and all the Rust code, so it takes a few minutes.
Jarvest then appears in your app list.

To add the widget, touch and hold your home screen, choose **Widgets**, and find
**Jarvest**.

## Share the app with testers

**1. Create your signing key (once):**

```bash
scripts/create-keystore.sh
```

This repository's working copy already has a key if `android/release.keystore`
exists, and the script won't replace it.

> **Back up `android/release.keystore` and `android/keystore.properties` somewhere
> private.** Android only installs an update if it's signed with the same key. If
> you lose the key, testers must uninstall the app, which deletes their data unless
> they saved a backup. Never share these files or commit them. Git ignores them.

**2. Build the APK:**

```bash
cd android
./gradlew assembleRelease
```

**3. Send this file to your testers:**
`android/app/build/outputs/apk/release/app-release.apk`

**4. Testers install it** by opening the file on their phone. Android asks them to
allow installs from that source the first time. It runs on Android 8 or newer, on
64-bit phones.

**To ship an update,** increase `versionCode` and `versionName` in
`android/app/build.gradle.kts`, then build again.

## Back up and restore your data

In the app, open **Settings** using the gear icon.

- **Save backup** writes one `.sqlite` file to a location you choose.
- **Restore from backup** replaces all current data with that file. A file that
  isn't a valid backup is rejected, and nothing changes.

Uninstalling the app deletes its data, so save a backup first. Android's automatic
cloud backup and device-to-device transfer are deliberately off, so a backup file
is also how you move to a new phone.

## Change the database

Never edit a released file in `core/src/sql/`, because testers' phones have already
applied it. Add a new numbered file instead, then add it to the `MIGRATIONS` list in
`core/src/db.rs`. Existing data and older backups are upgraded automatically.
