# Speech Pipeline Test — Android

A minimal Kotlin app that loads the quantized LID + IndicConformer + Whisper
pipeline via ONNX Runtime Mobile and runs it against real microphone input,
on a physical device. It exists to answer one question: **does this pipeline
actually fit in memory and run correctly on an 8GB Android phone?** It is a
feasibility smoke test, not a polished product — see `PROGRESS.md` at the
repo root for what's validated and what's still open.

## Prerequisites

- Android SDK with `platform-tools`, a `build-tools`/`platforms` version
  matching `compileSdk` in `app/build.gradle.kts`, installed at the path in
  `local.properties` (`sdk.dir`) — edit that file if your SDK lives elsewhere.
- A Gradle distribution. This project has no `gradlew` wrapper committed; use
  any local Gradle 8.x install, e.g. one already cached by Android Studio:
  ```bash
  GRADLE="$HOME/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle"
  ```
  (adjust the version/hash to whatever's actually present under
  `~/.gradle/wrapper/dists/`, or point at any `gradle` binary on your PATH).
- A physical **arm64-v8a** Android device (the emulator's x86_64 image will
  not represent real performance) with USB debugging enabled and authorized.
- The three quantized model directories, produced by the export/quantize
  tooling in `tools/` and `scripts/measure_footprints.py` (see
  `PROGRESS.md` §"Model export & quantization" for exact commands) — this
  README assumes you already have them locally as:
  ```
  <indic>/encoder.onnx (+ .data), ctc_decoder.onnx (+ .data),
         preprocessor.onnx, vocab.json, language_masks.json
  <lid>/lid.onnx
  <whisper>/encoder.onnx, decoder.onnx, tokens.txt, mel_filters_80x201.bin
  ```

## 1. Build the APK

```bash
cd android
"$GRADLE" assembleDebug --console=plain
```
Output: `android/app/build/outputs/apk/debug/app-debug.apk` (~22MB — models
are not bundled, see "Why models are pushed separately" below).

## 2. Get the models onto the device — the non-obvious part

Models are **not** in the APK; they're loaded from the app's private internal
storage at runtime (`filesDir/models/...`). Getting ~770MB of files there
takes two steps, not one — a plain `adb push` into the app's directory
will install fine but **fail to load** (`OrtException ... failed: system
error number 13`).

**Why:** files written by `adb push` are owned by the `shell` UID. Android's
scoped-storage FUSE layer blocks the app's own process from opening files it
doesn't own, regardless of Unix permission bits — even inside its own
`Android/data/<pkg>/files/` directory. `run-as <pkg>` runs as the app's real
UID, so the fix is: push to a neutral, shell-owned scratch path, then have
`run-as` copy from there into the app's true internal storage (`/data/data`,
never subject to the FUSE restriction).

```bash
SDK="$HOME/Library/Android/sdk"
ADB="$SDK/platform-tools/adb"
PKG="com.sih.speechtest"

# 1. Push to a shell-owned scratch location (adjust local source paths)
$ADB push /path/to/lid.onnx      /data/local/tmp/models_src/lid.onnx
$ADB push /path/to/indic         /data/local/tmp/models_src/indic
$ADB push /path/to/whisper       /data/local/tmp/models_src/whisper
$ADB shell chmod -R a+rX /data/local/tmp/models_src

# 2. Copy into the app's real internal storage, as the app's own UID
$ADB shell run-as $PKG mkdir -p files/models
$ADB shell run-as $PKG cp -r /data/local/tmp/models_src/lid.onnx  files/models/lid.onnx
$ADB shell run-as $PKG cp -r /data/local/tmp/models_src/indic     files/models/indic
$ADB shell run-as $PKG cp -r /data/local/tmp/models_src/whisper   files/models/whisper

# 3. Verify (should list real files, owned by the app, not "shell")
$ADB shell run-as $PKG ls -la files/models/
```
This only needs redoing when the model files themselves change — reinstalling
the APK after a code change does **not** clear `files/models/`.

## 3. Install and launch

```bash
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start --user 0 -n $PKG/.MainActivity
```

Two device-specific gotchas seen on a OnePlus/OxygenOS phone, may not apply
to other OEMs:
- The **first** install of a new/unsigned debug APK shows an on-device
  "Install anyway?" confirmation that `adb install` cannot dismiss — you
  must tap it on the phone. Reinstalls of the same app after that are silent.
- If the device has a **Work profile** / secondary user, `adb shell am start`
  without `--user 0` can fail with `SecurityException: Shell does not have
  permission to access user <n>` or silently target the wrong profile. Use
  `adb shell pm list users` to check, and always pass `--user 0` explicitly.

## 4. Use it

On launch the app loads all three ONNX sessions and reports load time +
memory (`App PSS` / native heap / device available RAM) at the top of the
screen — this is the actual answer to "does it fit." Once loaded:

1. Tap **Record**, speak, tap **Stop**.
2. The app runs LID first; if English it routes to Whisper, otherwise to
   IndicConformer's 9-way parallel CTC decode (matching the desktop
   pipeline's validated routing rule — see `PROGRESS.md`).
3. Results, per-model timings, and a fresh memory reading are shown on
   screen — screenshot or `adb exec-out screencap -p > out.png` to capture.

Crashes/exceptions are caught and printed to the screen directly (not just
logcat) specifically so they're visible without a debugger attached.

## Project layout

```
android/
  app/src/main/java/com/sih/speechtest/
    Lid.kt         LID: waveform -> 9-way softmax, argmax
    IndicAsr.kt     preprocessor -> encoder -> CTC decoder, 9-way language vote
    WhisperAsr.kt   mel spectrogram -> encoder -> KV-cache decoder loop
    MainActivity.kt mic capture, model loading, orchestration, UI
```
No JNI/native code of our own — everything runs through ONNX Runtime
Mobile's Java bindings (`onnxruntime-android` from Maven Central). Feature
extraction for all three models runs as ONNX ops (baked in at export time),
**except** Whisper's mel spectrogram, which is hand-implemented in Kotlin
(`WhisperAsr.logMelSpectrogram`) since we used sherpa-onnx's prebuilt
encoder/decoder rather than exporting our own preprocessor for it.
