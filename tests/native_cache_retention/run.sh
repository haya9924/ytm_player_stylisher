#!/bin/sh
# Compiles and runs the JVM tests for the native account-scoped cache preference.
#
# The extension sources are compiled with the JVM replacements in stubs/ and the reflected
# cache mirrors in fakes/ so no APK, Android SDK or Gradle build is needed.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../.." && pwd)
extensions="$root/ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym"
work="$here/build"
classes="$work/classes"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    javac="$JAVA_HOME/bin/javac"
    java="$JAVA_HOME/bin/java"
elif [ -x "$root/tools/jdk21/bin/javac" ]; then
    javac="$root/tools/jdk21/bin/javac"
    java="$root/tools/jdk21/bin/java"
else
    javac=javac
    java=java
fi

rm -rf "$work"
mkdir -p "$classes"

"$javac" --release 8 -Xlint:-options -d "$classes" \
    "$extensions/NativeCacheRetention.java" \
    "$extensions/QualifiedPlayCounter.java" \
    "$extensions/ScopedPlaybackKeys.java" \
    "$extensions/CacheRetentionCriteria.java" \
    "$extensions/CacheSavingsEstimator.java" \
    "$extensions/CacheRetentionPreferences.java" \
    "$extensions/ScopedPlaybackRecordingBridge.java" \
    "$here/stubs/app/morphe/extension/ym/ScopedPlayHost.java" \
    "$here/stubs/app/morphe/extension/ym/GradientSettingsActivity.java" \
    "$here/stubs/android/app/Application.java" \
    "$here/stubs/android/content/Context.java" \
    "$here/stubs/android/content/SharedPreferences.java" \
    "$here/stubs/android/os/Build.java" \
    "$here/stubs/android/util/Log.java" \
    "$here/stubs/android/os/SystemClock.java" \
    "$here/fakes/audf.java" \
    "$here/fakes/aufr.java" \
    "$here/fakes/aufs.java" \
    "$here/fakes/augy.java" \
    "$here/NativeCacheRetentionTest.java"

exec "$java" -cp "$classes" NativeCacheRetentionTest
