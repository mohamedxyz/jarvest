# CLAUDE.md

Jarvest is a small Android checklist app in the style of Apple's Reminders: tasks with subtasks, nothing else. Plain Kotlin and Jetpack Compose, no database library, no Rust. The user is new to Android, so keep explanations beginner-friendly and answers short.

## Commands

- Unit tests and debug build: `cd android && ./gradlew testDebugUnitTest assembleDebug --no-daemon`. If Maven Central answers 429, wait a minute and run it again.
- Debug install on a phone: `cd android && ./gradlew installDebug`.
- Release APK: `cd android && ./gradlew assembleRelease` -> `android/app/build/outputs/apk/release/app-release.apk`. The signing key comes from `scripts/create-keystore.sh`; never commit or replace `android/release.keystore` or `android/keystore.properties`.
- Release from GitHub: run the Release workflow (Actions tab, or `workflow_dispatch`). It tags the commit with `v` + `versionName` and publishes the APK. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` first.

## How it works

- `Model.kt`: `Item` (a task or subtask) and `Checklist`, plain functions on plain lists, with all the rules and the JSON export/import. Tested in `src/test/.../ChecklistTest.kt`. Put new rules here, with a test.
- `Store.kt`: keeps the list in memory and saves the whole list to `filesDir/tasks.json` after each change. Settings (automatic delete) are in SharedPreferences.
- `ListScreen.kt`: the one main screen. `SettingsScreen.kt`: delete finished tasks automatically, export, import. `Theme.kt`: Apple-like light and dark colors that follow the phone.

## Rules to keep

- A main task with subtasks is done exactly when all its subtasks are. Ticking a main task ticks all its subtasks. Adding a subtask reopens its main task. Subtasks can't have subtasks.
- There is no trash, no archive and no other list. Delete is final, with an Undo message that lasts a few seconds. Deleting a main task deletes its subtasks. Automatic delete only removes finished main tasks, never open ones.
- Rule functions never read the clock: the caller passes `today` (days since 1970).
- Import is untrusted input: `Checklist.fromJson` limits size and count and cleans bad rows.
- Keep it simple. Do not add features unless asked.
