#!/usr/bin/env bash
set -euo pipefail
cd /workspace/pi/android
source scripts/cloud-env.sh
test -x "$JAVA_HOME/bin/javac"
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
cd /workspace/pi
npm ci --ignore-scripts --cache /workspace/.npm-cache
cd /workspace/pi/android
npm ci --ignore-scripts --cache /workspace/.npm-cache
npm run bundle
npm run check
node --test test/runtime.test.mjs
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
