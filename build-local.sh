#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$android_sdk" || ! -f "$android_sdk/platforms/android-36/android.jar" ]]; then
  echo "ANDROID_HOME must point to an SDK containing platforms;android-36." >&2
  exit 2
fi

find_build_tools() {
  if [[ -n "${ANDROID_BUILD_TOOLS:-}" ]]; then
    printf '%s\n' "$ANDROID_BUILD_TOOLS"
    return
  fi
  local preferred="$android_sdk/build-tools/36.0.0"
  if [[ -d "$preferred" ]]; then
    printf '%s\n' "$preferred"
    return
  fi
  local candidate
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    local dir="$android_sdk/build-tools/$candidate"
    if [[ -x "$dir/aapt2" && -x "$dir/d8" && -x "$dir/zipalign" && -x "$dir/apksigner" ]]; then
      printf '%s\n' "$dir"
      return
    fi
  done < <(ls -1 "$android_sdk/build-tools" 2>/dev/null | sort -Vr || true)
  return 1
}

build_tools="$(find_build_tools || true)"
if [[ -z "$build_tools" ]]; then
  echo "No usable Android build-tools installation was found." >&2
  exit 2
fi
for tool in aapt2 d8 zipalign apksigner; do
  if [[ ! -x "$build_tools/$tool" ]]; then
    echo "Missing $build_tools/$tool" >&2
    exit 2
  fi
done

echo "Using Android build tools: $build_tools" >&2

build_dir="$project_dir/.local-build"
if [[ -d "$build_dir" ]]; then
  rm -rf -- "$build_dir"
fi
mkdir -p "$build_dir/compiled" "$build_dir/generated" "$build_dir/classes" \
  "$build_dir/dex" "$project_dir/dist"

android_jar="$android_sdk/platforms/android-36/android.jar"
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.rstlab.batteryrelay">#' \
  "$project_dir/app/src/main/AndroidManifest.xml" > "$build_dir/AndroidManifest.xml"
"$build_tools/aapt2" compile --dir "$project_dir/app/src/main/res" \
  -o "$build_dir/compiled/resources.zip"
"$build_tools/aapt2" link \
  -I "$android_jar" \
  --manifest "$build_dir/AndroidManifest.xml" \
  --java "$build_dir/generated" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code 5 \
  --version-name 1.3.1 \
  --auto-add-overlay \
  -o "$build_dir/resources.apk" \
  "$build_dir/compiled/resources.zip"

find "$project_dir/app/src/main/java" "$build_dir/generated" -name '*.java' -print \
  > "$build_dir/sources.list"
javac -encoding UTF-8 --release 8 -classpath "$android_jar" \
  -d "$build_dir/classes" @"$build_dir/sources.list"
jar --create --file "$build_dir/classes.jar" -C "$build_dir/classes" .
"$build_tools/d8" --min-api 26 --lib "$android_jar" \
  --output "$build_dir/dex" "$build_dir/classes.jar"

cp "$build_dir/resources.apk" "$build_dir/with-dex.apk"
(
  cd "$build_dir/dex"
  zip -q -j "$build_dir/with-dex.apk" classes*.dex
)
"$build_tools/zipalign" -p -f 4 "$build_dir/with-dex.apk" "$build_dir/aligned.apk"

release_keystore="${BATTERY_RELAY_RELEASE_KEYSTORE:-}"
if [[ -n "$release_keystore" ]]; then
  if [[ ! -f "$release_keystore" ]]; then
    echo "BATTERY_RELAY_RELEASE_KEYSTORE does not exist: $release_keystore" >&2
    exit 2
  fi
  : "${BATTERY_RELAY_STORE_PASSWORD:?BATTERY_RELAY_STORE_PASSWORD is required for release signing}"
  : "${BATTERY_RELAY_KEY_ALIAS:?BATTERY_RELAY_KEY_ALIAS is required for release signing}"
  : "${BATTERY_RELAY_KEY_PASSWORD:?BATTERY_RELAY_KEY_PASSWORD is required for release signing}"
  keystore="$release_keystore"
  store_password="$BATTERY_RELAY_STORE_PASSWORD"
  key_alias="$BATTERY_RELAY_KEY_ALIAS"
  key_password="$BATTERY_RELAY_KEY_PASSWORD"
  output="$project_dir/dist/BatteryRelay-1.3.1.apk"
else
  keystore="$project_dir/.dev-signing.jks"
  store_password="${BATTERY_RELAY_DEV_STORE_PASSWORD:-batteryrelay-dev}"
  key_alias="${BATTERY_RELAY_DEV_KEY_ALIAS:-battery-relay}"
  key_password="${BATTERY_RELAY_DEV_KEY_PASSWORD:-$store_password}"
  if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -noprompt -keystore "$keystore" -storepass "$store_password" \
      -keypass "$key_password" -alias "$key_alias" -keyalg RSA -keysize 3072 \
      -validity 3650 -dname "CN=Battery Relay Development,O=RST Lab,C=JP" >/dev/null
  fi
  output="$project_dir/dist/BatteryRelay-1.3.1-dev.apk"
  echo "WARNING: creating a DEVELOPMENT-signed APK. Configure BATTERY_RELAY_RELEASE_KEYSTORE and release passwords for an upgrade-compatible distribution APK." >&2
fi

rm -f -- "$output" "$output.idsig"
# apksigner supports env: password sources. Keep long-lived signing passwords out of the process
# command line so they are not exposed by process listings or diagnostic command capture.
(
  export BATTERY_RELAY_APKSIGNER_STORE_PASSWORD="$store_password"
  export BATTERY_RELAY_APKSIGNER_KEY_PASSWORD="$key_password"
  "$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false \
    --ks-pass env:BATTERY_RELAY_APKSIGNER_STORE_PASSWORD \
    --key-pass env:BATTERY_RELAY_APKSIGNER_KEY_PASSWORD \
    --out "$output" "$build_dir/aligned.apk"
)
if [[ -e "$output.idsig" ]]; then
  unlink "$output.idsig"
fi
"$build_tools/apksigner" verify --verbose --print-certs "$output"
echo "$output"
