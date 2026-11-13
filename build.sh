#!/usr/bin/env bash
# Builds the signed APK. Needs JDK 17+, the Android SDK (ANDROID_HOME set) and Gradle 8.7+
# (or Android Studio, which supplies its own Gradle).
set -e
cd "$(dirname "$0")"
if [ -x ./gradlew ]; then ./gradlew assembleRelease; else gradle assembleRelease; fi
echo
echo "APK: app/build/outputs/apk/release/app-release.apk"
