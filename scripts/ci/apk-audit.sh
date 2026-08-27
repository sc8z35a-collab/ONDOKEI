#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: apk-audit.sh APK [REPORT_DIR]}"
report_dir="${2:-artifacts/static-audit}"
expected_package="jp.rstlab.batteryrelay.debug"
mkdir -p "$report_dir"

latest_tool() {
  local name="$1"
  find "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/build-tools" -type f -name "$name" \
    | sort -V | tail -n 1
}

aapt2="$(latest_tool aapt2)"
apksigner="$(latest_tool apksigner)"
zipalign="$(latest_tool zipalign)"
test -x "$aapt2"
test -x "$apksigner"
test -x "$zipalign"
test -s "$apk"

sha256sum "$apk" | tee "$report_dir/sha256.txt"
unzip -t "$apk" | tee "$report_dir/zip-integrity.txt"
"$zipalign" -c -v 4 "$apk" | tee "$report_dir/zipalign.txt"
"$apksigner" verify --verbose --print-certs "$apk" | tee "$report_dir/signature.txt"
"$aapt2" dump badging "$apk" | tee "$report_dir/badging.txt"
"$aapt2" dump permissions "$apk" | tee "$report_dir/permissions.txt"
"$aapt2" dump xmltree --file AndroidManifest.xml "$apk" \
  | tee "$report_dir/manifest-tree.txt"

grep -Fq "package: name='$expected_package'" "$report_dir/badging.txt"
grep -Fq "minSdkVersion:'26'" "$report_dir/badging.txt"
grep -Fq "targetSdkVersion:'36'" "$report_dir/badging.txt"
grep -Fq "launchable-activity: name='jp.rstlab.batteryrelay.MainActivity'" \
  "$report_dir/badging.txt"

for permission in \
  android.permission.INTERNET \
  android.permission.ACCESS_NETWORK_STATE \
  android.permission.ACCESS_WIFI_STATE \
  android.permission.CHANGE_NETWORK_STATE \
  android.permission.CHANGE_WIFI_MULTICAST_STATE \
  android.permission.WAKE_LOCK \
  android.permission.FOREGROUND_SERVICE \
  android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE \
  android.permission.FOREGROUND_SERVICE_SPECIAL_USE \
  android.permission.POST_NOTIFICATIONS \
  android.permission.ACCESS_LOCAL_NETWORK; do
  grep -Fq "$permission" "$report_dir/permissions.txt"
done

grep -Eq ':allowBackup\([^)]*\)=(false|\(type 0x12\)0x0)' \
  "$report_dir/manifest-tree.txt"
grep -Fq 'jp.rstlab.batteryrelay.service.MonitorService' \
  "$report_dir/manifest-tree.txt"
grep -Fq ':foregroundServiceType(' "$report_dir/manifest-tree.txt"
grep -Fq 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$report_dir/manifest-tree.txt"

if grep -Eq 'android.permission.(READ|WRITE)_(CONTACTS|CALL_LOG|SMS)|android.permission.RECORD_AUDIO|android.permission.ACCESS_FINE_LOCATION|android.permission.CAMERA' \
  "$report_dir/permissions.txt"; then
  echo "Unexpected privacy-sensitive permission detected" >&2
  exit 1
fi

echo "APK audit passed: package/signature/ZIP/alignment/SDK/manifest/permissions" \
  | tee "$report_dir/result.txt"
