#!/usr/bin/env bash
# Build HPFlex.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Requires: ../sdk (build-tools r34 + platforms/android-34), JDK 17+, zip.
set -euo pipefail
cd "$(dirname "$0")"
SDK="$(cd ../sdk && pwd)"
BT="$SDK/android-14"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT=out

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "[2/6] aapt2 link (base apk + R.java)"
"$BT/aapt2" link \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 1 --version-name 0.1 \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res.zip"

echo "[3/6] javac"
find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -Xlint:-options -source 8 -target 8 -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" -d "$OUT/obj" @"$OUT/sources.txt"

echo "[4/6] d8 (dex)"
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 21 --release --output "$OUT/dex.jar" @"$OUT/classes.txt"
unzip -o -j -q "$OUT/dex.jar" classes.dex -d "$OUT"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -X unsigned.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign (debug key)"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias hpflex \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=HPFlex Debug, OU=dev, O=dev, C=DE"
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out HPFlex.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs HPFlex.apk | head -3
ls -la HPFlex.apk
echo "OK: $(pwd)/HPFlex.apk"
