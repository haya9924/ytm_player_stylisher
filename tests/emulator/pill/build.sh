#!/usr/bin/env bash
# Produce an x86-compatible APK for manual emulator verification, without Gradle.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out=$(realpath -m "${1:?usage: build.sh OUTPUT_DIRECTORY}")
sdk=${ANDROID_HOME:-$root/tools/android-sdk}
bin=$sdk/build-tools/36.0.0
platform=$sdk/platforms/android-36/android.jar
export JAVA_HOME=${JAVA_HOME:-$root/tools/jdk21}
export PATH="$JAVA_HOME/bin:$PATH"
java_src=$root/ym_morphe/extensions/ym/src/main/java/app/morphe/extension/ym
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/classes" "$work/dex"

javac -J-Xmx256m --release 8 -classpath "$platform" -d "$work/classes" \
    "$here/PreviewActivity.java" "$java_src"/{AudioVideoPill,CacheRetentionCriteria,CacheRetentionPreferences,GradientSettingsActivity,PlaybackNetworkPolicy,PlaybackPriorityState}.java
jar cf "$work/classes.jar" -C "$work/classes" .
"$bin/d8" -JXmx256m --thread-count 1 --min-api 26 --lib "$platform" --output "$work/dex" "$work/classes.jar"
"$bin/aapt2" link -I "$platform" --manifest "$here/AndroidManifest.xml" -o "$work/base.apk"
python3 - "$work/base.apk" "$work/dex/classes.dex" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_STORED) as apk:
    apk.write(sys.argv[2], 'classes.dex')
PY
"$bin/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
"$bin/apksigner" sign --ks "${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$out/pill-preview.apk" "$work/aligned.apk"
