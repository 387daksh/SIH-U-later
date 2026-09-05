# Android offline test app

This is a native Kotlin microphone UI using ONNX Runtime Android. It integrates Silero VAD for real-time pause and silence detection, segmenting continuous speech into discrete utterances passed immediately to the offline STT pipeline (Whisper for English and 9-language IndicConformer vote). Model files are deliberately staged outside the APK to keep the app lightweight and fast to iterate. The **Speak latest result** button uses Android's installed Text-to-Speech engine.

## Prepare the models

From the repository root:

```powershell
.\.venv\Scripts\python.exe .\scripts\stage_android_models.py
```

The generated bundle is ~771.70 MiB and remains untracked under `artifacts/android_models`. It includes:
- **Silero VAD ONNX**: Real-time voice activity detection with rolling 64-sample context buffer and adaptive hysteresis pause segmentation.
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

Grant microphone permission, tap **Start continuous listening**, speak naturally with pauses between sentences, and observe real-time speech detection and segment-by-segment offline transcription. Tap **Stop listening** when finished. Tap **Speak latest result** to play back the transcription via Android TTS.

## Adaptive Device Profiling

The app automatically categorizes the host hardware tier based on device RAM and CPU cores:
- **LOW Tier (<3 GB RAM / <=4 cores)**: Threshold 0.55, 600ms silence segmentation, 128ms padding.
- **MID Tier (3–6 GB RAM)**: Threshold 0.50, 500ms silence segmentation, 96ms padding.
- **HIGH Tier (>6 GB RAM)**: Threshold 0.45, 400ms silence segmentation, 64ms padding.

## Validation boundary

The Silero VAD ONNX model contract ([1, 576] input, [2, 1, 128] LSTM state carry-forward), ONNX frontends, model I/O, Whisper cache decoding, English routing/transcription, and Indic transcript parity were verified. Continuous audio capture with software linear resampling fallback and DC offset removal ensures stability across varying microphone hardware. Re-test this external-model build on the target phone before treating it as demo-ready.
