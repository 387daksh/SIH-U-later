# Android self-contained offline demo

This is a native Kotlin microphone demo using one dependency: ONNX Runtime Android. It records 16 kHz mono PCM, uses Whisper's language-token probability for English-vs-Indic routing, sends English to Whisper, and runs the existing nine-language IndicConformer vote for Indic audio.

## Prepare the models

From the repository root:

```powershell
.\.venv\Scripts\python.exe .\scripts\stage_android_models.py
```

The generated bundle is 771.70 MiB and remains untracked. The standalone demo APK embeds it and validates every file size before atomically installing it into app-private storage on first launch. It uses the hybrid INT8 Indic model, fp32 Whisper encoder, and INT8 Whisper decoder. The fully INT8 Whisper encoder failed the real-microphone accuracy check, so it is not the default.

## Build and run

1. Open the `android-bundled` directory in Android Studio and install Android SDK 35 when prompted.
2. Stage the embedded assets with `--output android-bundled/app/src/main/assets/models` and build the APK.
3. Install the APK on an Android 8+ physical phone, grant microphone access, and wait for the one-time model extraction.
4. Tap **Start recording**, speak, then tap **Stop and transcribe**.

The locally built demo is `dist\SIH-Offline-Speech-bundled.apk`. Install it with at least 2 GiB free internal storage:

```powershell
adb install -r .\dist\SIH-Offline-Speech-bundled.apk
```

## Validation boundary

The ONNX frontends, model I/O, Whisper cache decoding, real English routing/transcription, Indic transcript parity, bundle extraction checks, and APK compilation are checked locally. Re-test the rebuilt APK on the target phone before treating it as the demo release.
