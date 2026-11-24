#!/bin/sh
# The screen-sharing receive checks, on a JVM (no device): sh lorie-app/src/test/screenshare/run.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
src=$here/../../main/java/com/termux/x11/screenshare
jar=${ANDROID_JAR:-${ANDROID_HOME:-/usr/lib/android-sdk}/platforms/android-34/android.jar}
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -nowarn -d "$out" -cp "$jar" "$here/android/util/Log.java" "$here/com/termux/x11/AudioRoute.java" \
    "$src/AvcReceiver.java" "$src/HevcPicture.java" "$src/MediaClock.java" "$src/SrtpStream.java" \
    "$src/DirectSession.java" "$src/KeyframeFeedback.java" "$src/AudioReceiver.java" "$src/AppleLogin.java" \
    "$src/ControlWriter.java" "$src/MediaOffer.java" "$src/ScreenShareSession.java" "$src/ScreenShareStore.java" \
    "$src/ScreenShareSizing.java" "$src/CursorUpdates.java" "$src/ControlReader.java" \
    "$here/com/termux/x11/screenshare/HevcPictureCheck.java" "$here/com/termux/x11/screenshare/FrameAssemblyCheck.java" 2>&1 | grep -v '^Note:' || true
java -cp "$out:$jar" com.termux.x11.screenshare.HevcPictureCheck && echo "HevcPictureCheck ok"
java -cp "$out:$jar" com.termux.x11.screenshare.FrameAssemblyCheck
