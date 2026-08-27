#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$android_sdk" || ! -f "$android_sdk/platforms/android-36/android.jar" ]]; then
  echo "ANDROID_HOME must point to an SDK containing platforms;android-36." >&2
  exit 2
fi

build_tools="${ANDROID_BUILD_TOOLS:-$android_sdk/build-tools/35.0.0}"
for tool in aapt2 d8 zipalign apksigner; do
  if [[ ! -x "$build_tools/$tool" ]]; then
    echo "Missing $build_tools/$tool" >&2
    exit 2
  fi
done

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
  --version-code 4 \
  --version-name 1.3.0 \
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

keystore="${BATTERY_RELAY_KEYSTORE:-$project_dir/.dev-signing.jks}"
store_password="${BATTERY_RELAY_STORE_PASSWORD:-batteryrelay-dev}"
key_alias="${BATTERY_RELAY_KEY_ALIAS:-battery-relay}"
key_password="${BATTERY_RELAY_KEY_PASSWORD:-$store_password}"
if [[ ! -f "$keystore" ]]; then
  keytool -genkeypair -noprompt -keystore "$keystore" -storepass "$store_password" \
    -keypass "$key_password" -alias "$key_alias" -keyalg RSA -keysize 3072 \
    -validity 3650 -dname "CN=Battery Relay Development,O=RST Lab,C=JP" >/dev/null
fi

output="$project_dir/dist/BatteryRelay-1.3.0.apk"
rm -f -- "$output.idsig"
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
  --v4-signing-enabled false \
  --ks-pass "pass:$store_password" --key-pass "pass:$key_password" \
  --out "$output" "$build_dir/aligned.apk"
if [[ -e "$output.idsig" ]]; then
  unlink "$output.idsig"
fi
"$build_tools/apksigner" verify --verbose --print-certs "$output"
echo "$output"
