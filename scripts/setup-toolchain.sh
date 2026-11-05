#!/usr/bin/env bash
# Installs what the build needs beyond Android Studio's SDK. Safe to run repeatedly.
# Nothing here needs sudo, and your shell config isn't changed.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
ndk_version="$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$root/android/app/build.gradle.kts")"

if [ ! -x "$sdkmanager" ]; then
    echo "Android SDK command-line tools not found at $sdkmanager." >&2
    echo "Install them from Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools." >&2
    exit 1
fi

if [ ! -x "$HOME/.cargo/bin/rustup" ]; then
    echo "Installing rustup..."
    temp="$(mktemp -d)"
    trap 'rm -rf "$temp"' EXIT
    base="https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu"
    curl -sSf -o "$temp/rustup-init" "$base/rustup-init"
    curl -sSf -o "$temp/rustup-init.sha256" "$base/rustup-init.sha256"
    (cd "$temp" && echo "$(cut -d' ' -f1 rustup-init.sha256)  rustup-init" | sha256sum -c -)
    chmod +x "$temp/rustup-init"
    "$temp/rustup-init" -y --no-modify-path --profile minimal --default-toolchain none
fi
export PATH="$HOME/.cargo/bin:$PATH"

echo "Installing the pinned Rust toolchain and Android targets..."
(cd "$root" && rustup toolchain install)

if ! cargo ndk --version >/dev/null 2>&1; then
    echo "Installing cargo-ndk..."
    (cd "$root" && cargo install cargo-ndk --locked)
fi

# The Android build downloads the platform it needs by itself.
if [ ! -d "$sdk/ndk/$ndk_version" ]; then
    echo "Installing Android NDK $ndk_version..."
    # "yes" stops with an error once sdkmanager finishes, so success is checked below.
    yes | "$sdkmanager" --install "ndk;$ndk_version" >/dev/null || true
    if [ ! -d "$sdk/ndk/$ndk_version" ]; then
        echo "Couldn't install Android NDK $ndk_version. Install it from Android Studio's SDK Manager." >&2
        exit 1
    fi
fi

if [ ! -f "$root/android/local.properties" ]; then
    echo "sdk.dir=$sdk" >"$root/android/local.properties"
fi

echo "Done."
