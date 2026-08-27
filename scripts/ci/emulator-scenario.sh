#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: emulator-scenario.sh APK REPORT_DIR}"
report_dir="${2:?usage: emulator-scenario.sh APK REPORT_DIR}"
package="jp.rstlab.batteryrelay.debug"
activity="jp.rstlab.batteryrelay.MainActivity"
mkdir -p "$report_dir/screenshots"

snapshot() {
  local name="$1"
  adb exec-out screencap -p > "$report_dir/screenshots/$name.png"
  adb shell uiautomator dump "/sdcard/$name.xml" >/dev/null || true
  adb pull "/sdcard/$name.xml" "$report_dir/$name.xml" >/dev/null 2>&1 || true
}

assert_alive() {
  adb shell pidof "$package" | tr -d '\r' | grep -Eq '^[0-9]+'
}

assert_activity() {
  adb shell dumpsys activity activities > "$report_dir/activities.txt"
  grep -Fq "$package/$activity" "$report_dir/activities.txt"
}

assert_sampler_wakelock() {
  local output="$1"
  adb shell dumpsys power > "$output"
  grep -Fq 'BatteryRelay:ContinuousSampling' "$output"
}

adb wait-for-device
adb logcat -c || true
adb logcat -b crash -c || true
adb install -r -t "$apk" | tee "$report_dir/install.txt"

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if (( api >= 33 )); then
  adb shell pm revoke "$package" android.permission.POST_NOTIFICATIONS || true
fi
adb shell am force-stop "$package"
adb shell am start -W -n "$package/$activity" | tee "$report_dir/first-launch.txt"
sleep 3
if (( api >= 33 )); then
  # Exercise denial without depending on the localized system-dialog button label.
  adb shell pm revoke "$package" android.permission.POST_NOTIFICATIONS || true
  adb shell input keyevent BACK || true
fi
assert_alive
snapshot "01-first-launch-permission-denied"

if (( api >= 33 )); then
  adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
fi
adb shell am start -W -n "$package/$activity" > "$report_dir/permission-granted-launch.txt"
sleep 2
assert_activity
snapshot "02-permission-granted"

adb shell dumpsys activity services "$package" > "$report_dir/services-foreground.txt"
grep -Fq 'MonitorService' "$report_dir/services-foreground.txt"
grep -Eq 'isForeground=true|foregroundServiceType|foregroundId=' \
  "$report_dir/services-foreground.txt"
assert_sampler_wakelock "$report_dir/power-foreground.txt"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
assert_alive
snapshot "03-rotated"
adb shell settings put system user_rotation 0

adb shell input keyevent HOME
sleep 2
assert_alive
adb shell dumpsys activity services "$package" > "$report_dir/services-background.txt"
grep -Fq 'MonitorService' "$report_dir/services-background.txt"
assert_sampler_wakelock "$report_dir/power-background.txt"
adb shell am start -W -n "$package/$activity" > "$report_dir/background-return.txt"
sleep 2
assert_activity
snapshot "04-background-return"

adb shell svc wifi disable || true
adb shell svc data disable || true
sleep 3
assert_alive
snapshot "05-network-offline"
adb shell svc wifi enable || true
adb shell svc data enable || true
sleep 5
assert_alive
snapshot "06-network-restored"

adb shell am send-trim-memory "$package" COMPLETE \
  | tee "$report_dir/trim-memory.txt" || true
sleep 2
assert_alive
adb shell am force-stop "$package"
adb shell am start -W -n "$package/$activity" | tee "$report_dir/process-restart.txt"
sleep 3
assert_alive
assert_activity
assert_sampler_wakelock "$report_dir/power-after-restart.txt"
snapshot "07-process-restart"

adb shell dumpsys meminfo "$package" > "$report_dir/meminfo.txt"
adb shell dumpsys package "$package" > "$report_dir/package.txt"
adb shell dumpsys notification --noredact > "$report_dir/notifications.txt" || true
adb logcat -d -v threadtime > "$report_dir/logcat.txt"
adb logcat -b crash -d -v threadtime > "$report_dir/logcat-crash.txt" || true

if grep -Eq "Process: $package|Cmdline: $package|ANR in $package" \
  "$report_dir/logcat-crash.txt" "$report_dir/logcat.txt"; then
  echo "Application Java/native crash or ANR detected" >&2
  exit 1
fi

echo "Emulator lifecycle, service, wake-lock, permission, rotation, network, restart and low-memory scenario passed" \
  | tee "$report_dir/result.txt"
