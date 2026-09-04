# Android offline test app

This is a small native Kotlin microphone UI using ONNX Runtime Android. It keeps the verified Whisper English routing/transcription and nine-language IndicConformer vote, but model files are deliberately excluded from the APK. The **Speak result** button uses Android's installed Text-to-Speech engine.

## Prepare the models

From the repository root:

```powershell
.\.venv\Scripts\python.exe .\scripts\stage_android_models.py
```

The generated bundle is 769.48 MiB and remains untracked under `artifacts/android_models`. It uses the hybrid INT8 Indic model, FP32 Whisper encoder, and INT8 Whisper decoder. The fully INT8 Whisper encoder failed the real-microphone accuracy check, so it is not used.

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

Grant microphone permission, tap **Start recording**, speak, then tap **Stop and transcribe**. The APK is ARM64-only. TTS remains offline during use, but the desired language voice must already be installed in Android's Text-to-Speech settings.

## Validation boundary

The ONNX frontends, model I/O, Whisper cache decoding, real English routing/transcription, and Indic transcript parity were checked on the host. The earlier model-bundled build also completed repeated Hindi inference on an Android 16 phone and plateaued around 1.3 GiB RSS. Re-test this external-model build on the target phone before treating it as demo-ready.
