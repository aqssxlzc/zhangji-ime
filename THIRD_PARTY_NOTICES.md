# Third-Party Notices / 第三方说明

本仓库的源码不包含第三方模型与二进制。构建时由 `scripts/fetch-deps.sh` 下载：

## sherpa-onnx

- https://github.com/k2-fsa/sherpa-onnx
- License: Apache-2.0
- Used for: C++/JNI ASR and punctuation runtime; Android AAR `sherpa-onnx-2.0.2.aar`

## ONNX Runtime

- https://github.com/microsoft/onnxruntime
- License: MIT
- Used for: `libonnxruntime.so` included in the sherpa-onnx Android AAR

## Kotlin Standard Library

- https://github.com/JetBrains/kotlin
- License: Apache-2.0
- Used for: runtime dependency of the sherpa-onnx AAR

## Paraformer-zh ASR model

- https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14
- Converter/host: k2-fsa (sherpa-onnx)
- Original model: FunASR / Alibaba
- Please read the model repository and FunASR license/terms before redistribution or commercial use.

## CT-Transformer Chinese-English punctuation model (int8)

- https://github.com/k2-fsa/sherpa-onnx/releases/tag/punctuation-models
- Original model: FunASR / Alibaba
- Please read the model repository and FunASR license/terms before redistribution or commercial use.

## Android framework / SDK

- Android `android.jar` and build tools are downloaded from Google; use is subject to the Android SDK terms.

## Notes

The app itself is a device-specific personal project. The model files are **not** committed to this repository; the build script downloads them. Their licenses are independent from the source code license.
