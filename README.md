# Jarvest

A simple checklist for Android, in the style of Apple's Reminders.

- Add tasks. Tap a task to rename it, and add subtasks under it.
- Tick a task with the ring. A task with subtasks finishes by itself when its last subtask is ticked.
- Long press a task to select it, then select more and tap Delete. Undo is offered.
- Settings: delete finished tasks automatically (after 1, 7 or 30 days), and export or import your tasks as a file.

Everything stays on the phone. It follows the phone's light or dark setting.

## Build

You need Java 21 and the Android SDK.

```
cd android
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug        # on a connected phone
```

## Release

1. Create a signing key once with `scripts/create-keystore.sh`, and add the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS` secrets to the GitHub repository.
2. Raise `versionCode` and `versionName` in `android/app/build.gradle.kts`.
3. Run the Release workflow from the Actions tab. It publishes `jarvest-v<version>.apk`.

Keep the same signing key for every release, or phones can't update the app.
