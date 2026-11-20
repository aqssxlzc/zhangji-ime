#!/usr/bin/env bash
set -euo pipefail

KS_PASS="${VOICEIME_KS_PASS:-voiceime}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
VENDOR="$ROOT/vendor"
BUILD="$ROOT/build"
DIST="$ROOT/dist"

SDK="$VENDOR/android.jar"
AAR="$VENDOR/aar/classes.jar"
KOTLIN="$VENDOR/kotlin-stdlib-2.2.21.jar"
KEYSTORE="$APP/voiceime.keystore"

for f in "$SDK" "$AAR" "$KOTLIN"; do
    if [ ! -f "$f" ]; then
        echo "missing: $f" >&2
        exit 1
    fi
done
if [ ! -f "$APP/assets/para/model.int8.onnx" ]; then
    echo "missing model asset" >&2
    exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "generating keystore ..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -alias voiceime \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=VoiceIME,O=Local,C=CN" >/dev/null
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "== aapt2 compile"
aapt2 compile --dir "$APP/res" -o "$BUILD/resources.zip"

echo "== aapt2 link"
aapt2 link -o "$BUILD/base.apk" \
    -I "$SDK" \
    --manifest "$APP/AndroidManifest.xml" \
    "$BUILD/resources.zip"

echo "== javac"
find "$APP/src" -name '*.java' | sort > "$BUILD/sources.txt"
# 不使用 -bootclasspath android.jar：它不含 lambda 所需的 java.lang.invoke，
# 这里用 JDK 的引导类编译，lambda 交给 d8 desugar（代码只用了 Java 8 的 API）。
javac -source 8 -target 8 -Xlint:-options \
    -classpath "$SDK:$AAR:$KOTLIN" \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt"

echo "== jar"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .

echo "== d8"
d8 --release --min-api 26 --lib "$SDK" \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" "$AAR" "$KOTLIN"

echo "== assemble"
python3 "$ROOT/assemble.py" \
    "$BUILD/base.apk" "$BUILD/unsigned.apk" \
    "$BUILD/dex/classes.dex" \
    "$APP/assets" "$VENDOR/jni/arm64-v8a"

echo "== zipalign"
zipalign -p -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== sign"
apksigner sign \
    --ks "$KEYSTORE" --ks-key-alias voiceime \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version 26 \
    --out "$DIST/voice-ime.apk" \
    "$BUILD/aligned.apk"

echo "== verify"
apksigner verify --verbose "$DIST/voice-ime.apk" | head -20
aapt dump badging "$DIST/voice-ime.apk" | head -6
ls -lh "$DIST/voice-ime.apk"
