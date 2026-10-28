#!/usr/bin/env bash
# Reproducible hand-build of the judge APK without gradle.
# Needs: JDK 17+ and Android SDK build-tools 34 + platform android-34.
#   BUILD_TOOLS=/path/to/build-tools-34 PLATFORM=/path/to/android-34/android.jar ./build.sh keystore.jks
set -euo pipefail
cd "$(dirname "$0")"
BT="${BUILD_TOOLS:?set BUILD_TOOLS}"
PLATFORM="${PLATFORM:?set PLATFORM}"
KS="${1:?usage: build.sh keystore.jks}"
# Small generated UI icons, encoded to keep all build inputs in source.
mkdir -p app/assets
base64 -d > app/assets/favicon.ico <<'ICON'
AAABAAEAICAAAAEAIADpAAAAFgAAAIlQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAALBJREFUeJztlTEOgCAMRasxLtYTchDPwEG8oROLTgwa1N8CwZj+DQL8By1tN/G8U0P1Lc0NwAAMgIhoyD1gW8bTmH0Q7e8khehqdicJBAyAmktBquYAAg0BaG6P6jUJU+bsg0usWzUA4hCkzJ/miwOUVlEAzSuIAZ5ircmD74cArWqp2yN7Vb0gmrEPTvv9oqBSXKsME4E5wD6IuxwqUTeMym3B2QAl9f1vaAAG8HuAAzElNrgTMrZcAAAAAElFTkSuQmCC
ICON
base64 -d > app/assets/icon-192.png <<'ICON'
iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAAEZElEQVR4nO3cTXLTQBSF0YaiMsFZYRaSNXgh7DCjTGAAVTGJbf1brXfP2QCK9L7uxlby7efp+XeDUN/3vgDYkwCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIo6O31ae9LOIxvP0/Pv/e+COa7N+yn8/sDr+SYBHAgc1Z2EdwngA6tfYQRwW0C2Nkjz+tC+EoAG+vtP6Qi+J8AFuhtuMcSwQcB3HHUAR9DBH/5HuCGysPfWv2fbywBXJEyHCk/5z2OQJ+kDkXqkcgOcCF1+FvL/dkF8E/qAFxKvAcC4D9pEQig5T30IUn3Iz6ApIc9Rcp9if4UKOUhL1X5E6LYHcDwj1f5XkUGUPmBbqXqPYsLoOqDZJ6oAAw/n8UEYPi5JiIAw88t5QMw/NxTOgDDv66K97N0ADCkbAAVVyvWVzIAw89Y5QIw/ExRKoAjDP/p/P6y9zXwoczboL0P/5jBf3t9+vWIa1mi2puhJQLoefjXWPF7CqNaAKWOQL1Z67jj2LSdH3tfQEUG9jhKHIFa6+MYtPXg93AUcgTiqkes+naW9dkBFtpjKPfeCSrtAnaABazIxyeAGU7n95c9h1946xHARL0MXy/XcXRlAqh0Lh1LBMuVCeARDFw9Ajg4US4jAKIJYKSeV9qer613AiBaqQC2+iToCCvsEa6xR6UC4DF6ePFwLQIoxC4wnQAGGKraBEA0AQzY+9XjKY50rb0oF0DiO0HMVy6ALVhZ6xLASCKoSQBFCHQeAUxgyOoRwEQiqEUARCsZwNYfhfa2C+xxPVXeByoZAIwlgJl62wWYxx/HPTARLieAAzL463EEWmCn/3wa/hXZAQ6it8Gv8tKhADrX2+BXU+bPo1/zqM+qt/itsZ4Hv8rq35odoDs9D35rtYa/NTvAJubuCIb/8ewAG7g2yPei6H3wK7MD7OR0fn850uBXXP1bEwAjVB3+1nwRxoDKw9+aAAhXOoDqq9fWEu5f6QCYL2H4WxMA4QTAFymrf2sC4JOk4W+t+PcAl3wnMCxt+FsL2gESHy7DYnaAS3aDr1IXiJgd4FLqw74l+X5E7gCX0neD5OFvLXQHuJQ+AOnid4BLabuB+AVwVZUQDPgwAdzQewSGex0CGLDj7xXv8u+mEcAIW0Zg0PclgAmWhGDQ+ySAicZEYNiPQwAzvb0+GfQCBEC0+G+CySYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCIJoAiCYAogmAaAIgmgCIJgCiCYBoAiCaAIgmAKIJgGgCINofvo4q9z2+FF8AAAAASUVORK5CYII=
ICON
rm -rf build && mkdir -p build/classes build/dex build/gen
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" --manifest app/AndroidManifest.xml \
  -A app/assets --java build/gen --min-sdk-version 26 --target-sdk-version 34 build/res.zip
javac --release 11 -encoding UTF-8 -cp "$PLATFORM" -d build/classes \
  app/src/dev/bouldering/judge/*.java
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:"${KS_PASS:?set KS_PASS}" --key-pass pass:"$KS_PASS" \
  --out build/tournament-judge.apk build/aligned.apk
"$BT/apksigner" verify build/tournament-judge.apk && echo OK build/tournament-judge.apk
