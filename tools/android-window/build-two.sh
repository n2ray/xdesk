#!/bin/sh
set -eu
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
out=${1:?pass an output directory}
here=$(dirname "$0")
mkdir -p "$out/classes"
javac --release 11 -classpath "$ANDROID_HOME/platforms/android-35/android.jar" -d "$out/classes" "$here/TwoDisplays.java"
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 33 --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
    --output "$out/two.jar" "$out"/classes/TwoDisplays*.class
