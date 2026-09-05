# Android offline test app

This is a native Kotlin microphone UI using ONNX Runtime Android. It integrates Silero VAD for real-time pause and silence detection, segmenting continuous speech into discrete utterances passed immediately to the offline STT pipeline (Whisper for English and 9-language IndicConformer vote). Model files are deliberately staged outside the APK to keep the app lightweight and fast to iterate. The **Speak latest result** button uses Android's installed Text-to-Speech engine.

## Prepare the models

From the repository root:

```powershell
.\.venv\Scripts\python.exe .\scripts\stage_android_models.py
```

The generated bundle is 771.70 MiB and remains untracked under `artifacts/android_models`. It includes:
- **Silero VAD ONNX v6.2.1**: Pinned by SHA-256 and used for real-time pause segmentation.
- **Whisper (English)**: Hybrid FP32 encoder + INT8 decoder.
- **IndicConformer (9 Indian Languages)**: Hybrid INT8 model with 9-language CTC decoding.

## Build and install over USB

From the repository root, with USB debugging enabled:

```powershell
$adb = ".\.android-sdk\platform-tools\adb.exe"

Push-Location .\android
.\gradlew.bat assembleDebug
Pop-Location

& $adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am force-stop org.sih.offlinespeech
& $adb shell rm -rf /data/local/tmp/sih_models
& $adb push .\artifacts\android_models /data/local/tmp/sih_models
& $adb shell chmod -R a+rX /data/local/tmp/sih_models
& $adb shell run-as org.sih.offlinespeech rm -rf files/models
& $adb shell run-as org.sih.offlinespeech mkdir -p files/models
& $adb shell run-as org.sih.offlinespeech cp -r /data/local/tmp/sih_models/. files/models/
& $adb shell am start -n org.sih.offlinespeech/.MainActivity
```

Grant microphone permission, leave **Routing: AUTO** selected, tap **Start continuous listening**, and speak naturally with pauses between sentences. Tap the routing button to force English or Indic only when diagnosing a routing failure; forced routing also avoids loading the unused ASR backend. Tap **Speak latest result** to use an installed offline Android TTS voice.

## VAD configuration

VAD uses the same acoustic settings on every phone: 0.50 speech threshold, 0.35 silence threshold, 500 ms end pause, and 96 ms pre-padding. Hardware tier is shown only as diagnostic information; RAM and CPU count do not determine whether audio contains speech.

## Capture phone metrics

Clear old inference logs before a test, transcribe several utterances, then capture latency and memory:

```powershell
.\.android-sdk\platform-tools\adb.exe logcat -c
# Run the microphone test on the phone.
.\scripts\measure_android.ps1
```

This writes raw `dumpsys meminfo`, structured `SIH_METRIC` inference events, and `report.json` under `artifacts/android_device/<timestamp>`.

## Validation boundary

The pinned VAD model contract, ONNX frontends, model bundle sizes, Whisper English smoke test, streaming resampler, Kotlin compilation, and debug APK build are checked locally. Continuous microphone segmentation, Android latency, TTS voice availability, thermals, and memory still require the target-phone run above.
