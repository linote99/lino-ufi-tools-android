#!/usr/bin/env bash
# Offline tests: ZTE web login/AD signing and AList listing/download against test/mock.py,
# then ADB pull + screen-mirroring protocol against test/mock-adbd.py
# (127.0.0.1 only; no device, no real servers). Needs JDK 17+, python3, the Android SDK and test/json.jar
# (org.json, e.g. https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
J=$SDK/platforms/android-34/android.jar
S=../app/src/main/java/top/linote/ufiinstaller
APK=$(ls ../dist/*.apk | head -1)   # any APK works as download payload
rm -rf out tmp && mkdir -p out tmp
javac -encoding UTF-8 -cp json.jar:"$J" -d out JvmTest.java $S/ZteWeb.java $S/ApkSource.java
python3 mock.py 18080 "$APK" 2> tmp/mock.log & M=$!
trap 'kill $M 2>/dev/null' EXIT
sleep 1
java -cp out:json.jar:"$J" top.linote.ufiinstaller.JvmTest tmp

# ADB pull / concurrent streams / scrcpy 5.0.1 protocol against test/mock-adbd.py (fake scrcpy server).
# Needs python3-cryptography. The real scrcpy server is only pushed to the mock's scratch root, never run.
A=$(mktemp -d)
mkdir -p "$A/root" "$A/work" "$A/out"
python3 mock-adbd.py 15576 "$A/root" 65536 > "$A/adbd.out" 2>&1 & D=$!
trap 'kill $M $D 2>/dev/null' EXIT
sleep 1
javac -encoding UTF-8 -cp "$J" -sourcepath ../app/src/main/java -d "$A/out" MirrorTest.java $S/AdbConnection.java $S/AdbKey.java $S/ScrcpyProtocol.java
java -cp "$A/out:$J" top.linote.ufiinstaller.MirrorTest 15576 "$A/work" "$A/root" "$A/mock-adbd.log" ../app/src/main/assets/scrcpy-server-v5.0.1
