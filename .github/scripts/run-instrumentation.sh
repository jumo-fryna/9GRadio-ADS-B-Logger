#!/usr/bin/env bash
set -euo pipefail
adb install -r artifacts/SkyLog-1090-debug.apk
adb install -r artifacts/SkyLog-1090-tests.apk
adb shell pm path com.radiosport.skylog1090
logger_data=$(adb shell run-as com.radiosport.skylog1090 pwd | tr -d '\r')
[[ "$logger_data" == */com.radiosport.skylog1090 ]]
echo "SkyLog private data: $logger_data; original package: com.radiosport.ninegradio"
adb shell am instrument -w -r com.radiosport.skylog1090.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.txt
if ! grep -Eq '^OK \(7 tests\)' instrumentation.txt; then
  adb logcat -d -s AndroidRuntime SkyLogService
  echo 'Android instrumentation tests failed or did not complete.' >&2
  exit 1
fi
