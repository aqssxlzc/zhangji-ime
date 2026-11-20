# 掌机输入法 (Handheld IME)

一个给 Android 掌机用的「无界面」本地语音输入法。**按住手柄热键说话，松开后自动识别并上屏**；录音时点亮机身侧面的红色 LED。ASR 与标点模型都常驻本机，音频和文字不联网。

> 当前适配：**Anbernic RG DS**（RK3568 / Android 14）。
> `evdev` 节点、LED `sysfs` 路径和手柄 scan code 都是按这台机器写的，移植到其它掌机需要调整 `InputDevices` / `Led`。

## 识别效果

```
语音：好我现在开始测试这一条就是通过这种方法输入的
输出：好，我现在开始测试这一条，就是通过这种方法输入的。
```

- ASR：Paraformer-zh int8（约 233MB），整段识别
- 标点：CT-Transformer 中英标点 int8（约 75MB），可开关
- 延迟：5.9 秒语音 → 松手后约 1.9 秒上屏（RK3568，4 线程）

## 特性

- **无可见键盘**：输入视图只有 1 像素高，完全透明
- **按住说话**：默认 R1，可在设置页捕获为其它手柄键（A/B/X/Y/L1/L2/R1/R2/Select/Start…）
- **录音 / 转写解耦**：上一段还在识别时，可以立刻开始下一段
- **红色 LED**：录音期间常亮（400ms 心跳强制写），松开立即熄灭
- **模型常驻**：前台 `ModelService` 在打开应用/开机后加载模型，输入法服务被回收也不会掉模型
- **自动标点**：本地标点模型，设置页可关闭
- **备用触发**：快捷设置磁贴、`adb broadcast`
- **纯本地**：应用不声明 `INTERNET` 权限

## 工作原理

```
┌──────────────┐  R1 / 自定义键   ┌─────────────────┐
│ JoypadMonitor│ ───────────────▶ │ VoiceImeService │
│ /dev/input/  │                  │ 1px 透明输入法   │
│ event9       │ ◀─── 录音 ────── │ AudioRecord     │
└──────────────┘                  └───────┬─────────┘
                                          │ commitText()
                                          ▼
                                   任意输入框
┌──────────────┐   常驻模型      ┌─────────────────┐
│ ModelService │ ──────────────▶ │ AsrEngine       │
│ 前台服务      │                 │ sherpa-onnx     │
│ (specialUse) │                 │ Paraformer-zh   │
└──────────────┘                 │ + 标点模型       │
                                 └─────────────────┘
```

- `JoypadMonitor` 直接读手柄 evdev，不依赖无障碍服务或 root
- `ModelService` 是 `specialUse` 前台服务，持有 `AsrEngine`；通知栏有一条低优先级常驻通知
- `AsrEngine` 先整段识别，再按设置调用离线标点模型
- `Led` 写 `/sys/class/leds/low_power/brightness` 控制红灯

## 目录结构

```
app/
  AndroidManifest.xml
  res/                          图标、字符串、IME 元数据
  src/com/rgds/voiceime/
    VoiceImeService.java        输入法服务、录音、上屏
    ModelService.java           常驻前台服务，加载/持有模型
    AsrEngine.java              sherpa-onnx：ASR + 标点
    ModelHolder.java            进程内共享引擎
    JoypadMonitor.java          evdev 热键监听
    KeyCapture.java             自定义热键捕获
    HotkeyConfig.java / KeyNames.java / Prefs.java
    Led.java                    红灯 sysfs
    SetupActivity.java          设置与诊断页
    Diag.java                   内存日志 + 外部日志文件
    ToggleTileService.java      快捷设置磁贴
    ToggleReceiver.java         广播触发
build.sh                        aapt2 + javac + d8 + 签名
assemble.py                     组装 APK（模型以 STORED 方式打包）
scripts/fetch-deps.sh           下载 Android SDK 依赖与模型
```

## 构建（Termux）

```bash
pkg install openjdk-21 aapt2 apksigner d8 python curl unzip tar git

git clone https://github.com/aqssxlzc/zhangji-ime.git
cd zhangji-ime

./scripts/fetch-deps.sh     # 约 400MB：android.jar / sherpa AAR / 两个模型
./build.sh                  # 产物：dist/voice-ime.apk（约 330MB）
```

`fetch-deps.sh` 会下载：

| 依赖 | 来源 | 放置位置 |
|---|---|---|
| android.jar (API 34) | Google SDK platform-34-ext7_r03 | `vendor/android.jar` |
| sherpa-onnx 2.0.2 AAR | GitHub `k2-fsa/sherpa-onnx` v2.0.2 | `vendor/aar/classes.jar`、`vendor/jni/arm64-v8a/` |
| Kotlin stdlib 2.2.21 | Maven Central | `vendor/kotlin-stdlib-2.2.21.jar` |
| Paraformer-zh int8 | Hugging Face `csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14` | `app/assets/para/` |
| CT-Transformer 标点 int8 | GitHub `punctuation-models` release | `app/assets/punc/` |

模型、`vendor/`、APK 和签名密钥都不会提交到仓库（见 `.gitignore`）。

### 自动发布

推送 `v*` tag 会触发 `.github/workflows/release.yml`：在 ubuntu runner 上 `fetch-deps.sh` → `build.sh` → 用仓库 secret 中的 keystore 签名 → 创建 GitHub Release。
需要在仓库 secrets 中配置 `VOICEIME_KEYSTORE_B64`（keystore 的 base64）和 `VOICEIME_KS_PASS`。
Release 说明可选放在 `.github/release-notes/<tag>.md`，缺省时由 GitHub 自动生成。

### 签名

首次 `build.sh` 会在 `app/voiceime.keystore` 生成一个本地测试签名。更新已安装的应用必须使用同一个 keystore，请自行备份。
密码默认 `voiceime`，可用环境变量覆盖：

```bash
VOICEIME_KS_PASS='your-password' ./build.sh
```

## 安装与使用

1. 安装 `dist/voice-ime.apk`
2. 打开「语音输入」：授予麦克风权限与通知权限
3. 点「打开输入法设置」启用本输入法，再点「选择当前输入法」选中它
4. 等待「本地模型 就绪」「标点模型 就绪」（ASR 约 16-20 秒）
5. 需要的话在「热键」里点「设置热键」，按手柄上要用的键（默认 R1）
6. 在任意输入框里**按住热键**说话，**松开**后识别并上屏

> **看不到键盘是正常的**：本输入法只有 1 像素，不显示任何键盘 UI。

### LED

- 录音期间：机身侧面红灯常亮
- 松开：立即熄灭
- 红灯节点：`/sys/class/leds/low_power/brightness`（RG DS）

### 标点与选项

- 「选项 → 自动添加标点」默认开启
- 标点由本地 int8 模型完成，约 0.08 秒/句
- 关闭后仍保留 ASR 原文，模型仍常驻以保持即时性

### 备用触发

- 下拉通知栏 → 编辑快捷设置 → 添加「语音输入」磁贴；点一下开始，再点一下结束
- adb / 其它应用：
  ```bash
  am broadcast -a com.rgds.voiceime.TOGGLE -p com.rgds.voiceime
  ```

## 设备适配（RG DS）

| 项目 | 本机值 | 代码位置 |
|---|---|---|
| 手柄输入节点 | `/dev/input/event9`，名称 `retrogame_joypad` | `InputDevices.findJoypadNode()` |
| 默认热键 R1 | evdev scan code `311` | `HotkeyConfig` |
| A/B/X/Y/L1/L2/R2… | `304/305/307/308/310/312/313/314/315` | `KeyNames` |
| 红灯 | `/sys/class/leds/low_power/brightness`（0666） | `Led` |
| SELinux | permissive（设备为 userdebug） | — |
| ABI | arm64-v8a | build.sh / assemble.py |

移植到其它掌机时，主要改 `InputDevices` 的节点查找、`Led` 的 LED 路径，以及 `KeyNames` 的按键名。若目标设备不允许直接读 `/dev/input`，需要改为无障碍服务或 root 方案。

## 已知限制

- 模型不随仓库分发，需要 `fetch-deps.sh` 下载
- 设备相关：依赖手柄 evdev 与 LED sysfs 可读写；其它机型需适配
- 识别结果会保留部分语气词（啊/嗯/呃 等）
- 单次录音上限 60 秒
- 模型常驻占用约 300–500MB 内存；2.9GB 内存的 RG DS 可运行，但后台大应用多时可能被系统回收
- 本 APK 只包含 arm64-v8a
- 没有网络识别、没有云端纠错；不需要也不申请 INTERNET 权限

## 隐私

- 所有音频、识别、标点均在本机完成
- 应用不声明 `INTERNET` 权限
- 日志仅存放在应用自己的目录，可在设置页查看/复制

## 第三方与许可

见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Apache-2.0
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — MIT
- [Kotlin](https://github.com/JetBrains/kotlin) — Apache-2.0
- Paraformer-zh 与 CT-Transformer 标点模型版权归原作者，使用前请阅读对应仓库的许可证

## 许可证

MIT © 2026 The zhangji-ime Authors。见 [LICENSE](LICENSE)。

## 致谢

- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- FunASR / Paraformer / CT-Transformer 模型作者
- Termux、Android 开源社区
