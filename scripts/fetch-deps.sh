#!/usr/bin/env bash
# 下载并解包构建所需依赖与本地模型。
# 模型文件体积较大，不提交到仓库。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
V="$ROOT/vendor"
A="$ROOT/app/assets"
C="$ROOT/.cache"

PLATFORM_URL="https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar"
KOTLIN_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar"
PARA_BASE="https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"
PUNC_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"

for t in curl unzip tar; do
    command -v "$t" >/dev/null 2>&1 || {
        echo "缺少命令: $t" >&2
        exit 1
    }
done

mkdir -p "$V/aar" "$V/jni/arm64-v8a" "$A/para" "$A/punc" "$C"

fetch() { # url output
    local url="$1" out="$2"
    if [ -s "$out" ]; then
        echo "已存在: $out"
        return 0
    fi
    echo "下载: $url"
    mkdir -p "$(dirname "$out")"
    curl -L --fail --retry 3 --retry-delay 2 -o "$out.part" "$url"
    mv "$out.part" "$out"
}

# 1. android.jar (API 34)
if [ ! -f "$V/android.jar" ]; then
    fetch "$PLATFORM_URL" "$C/platform-34.zip"
    rm -rf "$C/sdk"
    mkdir -p "$C/sdk"
    unzip -o -q "$C/platform-34.zip" 'android-34/android.jar' -d "$C/sdk"
    cp "$C/sdk/android-34/android.jar" "$V/android.jar"
    echo "已生成: $V/android.jar"
fi

# 2. sherpa-onnx AAR：classes.jar + arm64 JNI
if [ ! -f "$V/aar/classes.jar" ] || \
   [ ! -f "$V/jni/arm64-v8a/libonnxruntime.so" ] || \
   [ ! -f "$V/jni/arm64-v8a/libsherpa-onnx-jni.so" ]; then
    fetch "$AAR_URL" "$C/sherpa-onnx.aar"
    unzip -o -q "$C/sherpa-onnx.aar" 'classes.jar' -d "$V/aar"
    rm -rf "$C/aarjni"
    unzip -o -q "$C/sherpa-onnx.aar" \
        'jni/arm64-v8a/libonnxruntime.so' \
        'jni/arm64-v8a/libsherpa-onnx-jni.so' \
        -d "$C/aarjni"
    cp "$C/aarjni/jni/arm64-v8a/libonnxruntime.so" "$V/jni/arm64-v8a/"
    cp "$C/aarjni/jni/arm64-v8a/libsherpa-onnx-jni.so" "$V/jni/arm64-v8a/"
    echo "已解包: $V/aar/classes.jar + $V/jni/arm64-v8a/"
fi

# 3. Kotlin stdlib
fetch "$KOTLIN_URL" "$V/kotlin-stdlib-2.2.21.jar"

# 4. Paraformer-zh int8 ASR 模型
fetch "$PARA_BASE/model.int8.onnx" "$A/para/model.int8.onnx"
fetch "$PARA_BASE/tokens.txt" "$A/para/tokens.txt"

# 5. CT-Transformer 中英标点 int8 模型
if [ ! -f "$A/punc/model.int8.onnx" ] || [ ! -f "$A/punc/tokens.json" ]; then
    fetch "$PUNC_URL" "$C/punc-int8.tar.bz2"
    rm -rf "$C/punc"
    mkdir -p "$C/punc"
    tar -xjf "$C/punc-int8.tar.bz2" -C "$C/punc"
    model="$(find "$C/punc" -name 'model.int8.onnx' | head -n 1)"
    tokens="$(find "$C/punc" -name 'tokens.json' | head -n 1)"
    [ -n "$model" ] && [ -n "$tokens" ] || {
        echo "标点模型解包失败" >&2
        exit 1
    }
    cp "$model" "$A/punc/model.int8.onnx"
    cp "$tokens" "$A/punc/tokens.json"
    echo "已解包标点模型"
fi

echo
echo "依赖就绪："
ls -lh "$V/android.jar" "$V/aar/classes.jar" "$V/kotlin-stdlib-2.2.21.jar"
ls -lh "$V/jni/arm64-v8a/"
ls -lh "$A/para/" "$A/punc/"
