#!/usr/bin/env bash
# Reproducible hand-build of the judge APK without gradle.
# Needs: JDK 17+ and Android SDK build-tools 34 + platform android-34.
#   BUILD_TOOLS=/path/to/build-tools-34 PLATFORM=/path/to/android-34/android.jar ./build.sh keystore.jks
set -euo pipefail
cd "$(dirname "$0")"
BT="${BUILD_TOOLS:?set BUILD_TOOLS}"
PLATFORM="${PLATFORM:?set PLATFORM}"
KS="${1:?usage: build.sh keystore.jks}"
rm -rf build && mkdir -p build/classes build/dex build/gen
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest app/AndroidManifest.xml \
  -A app/assets --java build/gen --min-sdk-version 26 --target-sdk-version 34 build/res.zip
javac --release 11 -encoding UTF-8 -cp "$PLATFORM" -d build/classes \
  app/src/dev/bouldering/judge/JudgeServer.java app/src/dev/bouldering/judge/MainActivity.java
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:"${KS_PASS:?set KS_PASS}" --key-pass pass:"$KS_PASS" \
  --out build/tournament-judge.apk build/aligned.apk
"$BT/apksigner" verify build/tournament-judge.apk && echo OK build/tournament-judge.apk
