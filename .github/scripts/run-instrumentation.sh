#!/usr/bin/env bash
set -euo pipefail
adb install -r artifacts/9GRadio-ADS-B-Logger-debug.apk
adb install -r artifacts/9GRadio-ADS-B-Logger-parallel-test.apk
adb install -r artifacts/9GRadio-ADS-B-Logger-tests.apk
# Both package IDs must coexist and use separate private data/database directories.
original_package=com.radiosport.ninegradio
logger_package=com.radiosport.ninegradio.adsblogger
adb shell pm path "$original_package"
adb shell pm path "$logger_package"
original_data=$(adb shell run-as "$original_package" pwd | tr -d '\r')
logger_data=$(adb shell run-as "$logger_package" pwd | tr -d '\r')
[[ "$original_data" == */"$original_package" ]]
[[ "$logger_data" == */"$logger_package" ]]
[[ "$original_data" != "$logger_data" ]]
echo "Verified separate private database paths: $original_data/databases and $logger_data/databases"
adb shell am instrument -w -r com.radiosport.ninegradio.adsblogger.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.txt
# am instrument can return shell exit code 0 even when tests fail.
if ! grep -Eq '^OK \(4 tests\)' instrumentation.txt; then
  echo 'Android instrumentation tests failed or did not complete.' >&2
  exit 1
fi
