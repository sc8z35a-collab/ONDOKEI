#!/usr/bin/env bash
set -Eeuo pipefail

api_level=${1:?API level is required}
app_apk=${2:?application APK is required}
test_apk=${3:?instrumentation APK is required}
artifact_dir=${4:?artifact directory is required}
package=jp.rstlab.batteryrelay.debug
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"

collect_emulator_artifacts() {
  local final_dir="$artifact_dir/final"
  mkdir -p "$final_dir"
  timeout 15s adb logcat -d -v threadtime > "$final_dir/logcat.txt" 2>&1 || true
  timeout 15s adb logcat -b crash -d -v threadtime > "$final_dir/logcat-crash.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity services "$package" > "$final_dir/services.txt" 2>&1 || true
  timeout 15s adb shell dumpsys meminfo "$package" > "$final_dir/meminfo.txt" 2>&1 || true
  timeout 15s adb exec-out screencap -p > "$final_dir/final-screen.png" 2>/dev/null || true
}

trap collect_emulator_artifacts EXIT

instrumentation_dir="$artifact_dir/instrumentation"
mkdir -p "$instrumentation_dir"
printf 'Running ONDOKEI system audit on Android API %s\n' "$api_level"

adb install -r -t "$app_apk"
adb install -r -t "$test_apk"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS || true

timeout --foreground 12m adb shell am instrument -w -r \
  -e disableAnalytics true \
  "$runner" \
  | tee "$instrumentation_dir/instrumentation.txt"

tr -d '\r' < "$instrumentation_dir/instrumentation.txt" \
  | grep -Eq '^OK \([0-9]+ tests?\)$'

"$(dirname "$0")/emulator-scenario.sh" "$app_apk" "$artifact_dir/scenario"

collect_emulator_artifacts
trap - EXIT
