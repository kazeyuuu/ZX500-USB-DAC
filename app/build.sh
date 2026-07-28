#!/bin/bash
# Manual APK build (no Gradle). Requires Android SDK build-tools 37,
# platform-34 and a JDK. The signing key is local and never committed.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/37.0.0}"
AJ="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-34/android.jar}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
KEYSTORE="${APK_KEYSTORE:-$DIR/local.keystore}"
KEY_ALIAS="${APK_KEY_ALIAS:-zx500}"
KEY_PASS="${APK_KEY_PASS:-android}"

cd "$DIR"
rm -rf build
mkdir -p build/classes
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" \
  -storepass "$KEY_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ZX500 USB-DAC local build"
# -J emits R.java for the string resources. The UI text lives in res/values
# (English) and res/values-zh (Chinese); Android picks by system language.
mkdir -p build/gen
aapt package -f -m -M AndroidManifest.xml -S res -I "$AJ" -J build/gen -F build/app-res.apk
javac -classpath "$AJ" -source 17 -target 17 -d build/classes \
  src/com/zx/dac/*.java build/gen/com/zx/dac/R.java
d8 --lib "$AJ" --min-api 28 --output build build/classes/com/zx/dac/*.class
cp build/app-res.apk build/app-unsigned.apk; (cd build && aapt add app-unsigned.apk classes.dex)
zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out ../ZX-DAC.apk build/app-aligned.apk
echo "built ../ZX-DAC.apk"
