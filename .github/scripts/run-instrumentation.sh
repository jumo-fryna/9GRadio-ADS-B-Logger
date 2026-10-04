#!/usr/bin/env bash
set -euo pipefail
adb install -r artifacts/9GRadio-ADS-B-Logger-debug.apk
adb install -r artifacts/9GRadio-ADS-B-Logger-tests.apk
adb shell am instrument -w -r com.radiosport.ninegradio.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.txt
# am instrument can return shell exit code 0 even when tests fail.
if ! rg -q 'OK \([0-9]+ tests?\)' instrumentation.txt; then
  echo 'Android instrumentation tests failed or did not complete.' >&2
  exit 1
fi
