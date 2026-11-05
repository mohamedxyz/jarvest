#!/usr/bin/env bash
# Creates the key that signs your release APKs, with a random password saved next
# to it. Both files are git-ignored. Back them up somewhere private: Android only
# installs an update when it's signed with the same key as the installed app.
set -euo pipefail

android="$(cd "$(dirname "$0")/../android" && pwd)"
keystore="$android/release.keystore"
properties="$android/keystore.properties"

if [ -e "$keystore" ] || [ -e "$properties" ]; then
    echo "A signing key already exists, and replacing it would stop testers updating. Nothing changed." >&2
    exit 1
fi

password="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
umask 077
keytool -genkeypair -v \
    -keystore "$keystore" -storetype PKCS12 -storepass "$password" \
    -alias goaltracker -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Goal Tracker"
cat >"$properties" <<PROPERTIES
storeFile=release.keystore
storePassword=$password
keyAlias=goaltracker
keyPassword=$password
PROPERTIES
echo "Created $keystore and $properties. Back up both."
