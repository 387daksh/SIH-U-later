# Android offline test app

This is a native Kotlin microphone UI using ONNX Runtime Android. It integrates Silero VAD for real-time pause and silence detection, segmenting continuous speech into discrete utterances passed immediately to the offline STT pipeline (Whisper for English and 9-language IndicConformer vote). Model files are deliberately staged outside the APK to keep the app lightweight and fast to iterate. The **Speak latest result** button uses Android's installed Text-to-Speech engine.

It also contains the SUTRA transmission prototype: a compact binary multilingual envelope, app-specific Wi-Fi Direct discovery, paired-phone Bluetooth RFCOMM fallback, a durable outbox and receipt ledger, AES-GCM authenticated sessions, foreground receiving, automatic receiver TTS, bounded alert relay, metrics export, and received/playback semantic acknowledgements.

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

If the 800 MB self-contained APK has already completed its first-run model extraction, install this thin APK as an update. Do not uninstall or clear app storage:

```powershell
.\.android-sdk\platform-tools\adb.exe install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

Both variants use application ID `org.sih.offlinespeech`. The thin app first checks the original bundle path, `getExternalFilesDir(null)/models`, so the existing models are reused. It also accepts the internal `files/models` path used by the USB staging workflow below.

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

## Two-phone SUTRA test

Install the same APK and model bundle on both phones. No router, hotspot, Internet connection, or IP address is required: Android Wi-Fi Direct creates the local link.

1. Enable Wi-Fi on both phones and grant **Nearby devices** and notification permissions.
2. Enter the same 12+ character pairing phrase on both phones and tap **Secure session**. Different phrases are rejected by AES-GCM authentication.
3. Tap **Find nearby** on both phones. Only devices advertising the SUTRA DNS-SD service are listed.
4. On either phone, select the other phone and tap **Connect**. Accept Android's Wi-Fi Direct invitation if shown; socket roles are negotiated automatically.
5. Leave **Message: NORMAL**, start listening, and speak. The receiver displays and speaks the text; the sender shows `RECEIVED_ACK`, `PLAYBACK_STARTED`, and `PLAYBACK_FINISHED` with elapsed milliseconds.
6. Toggle **Message: ALERT** and speak a sentence containing a negation, number, emergency term, or location hint. `ALERT_CORE` is sent before the transcript and the receiver requests alarm audio focus.
7. Background the receiver. Its foreground `connectedDevice|mediaPlayback` service keeps the socket, outbox, deduplication and TTS alive.

For Bluetooth fallback, pair the phones once in Android Settings. Tap **BT receive** on one and **Refresh**, select that phone, then **BT connect** on the other. Messages are broadcast to all connected Wi-Fi receivers and the Bluetooth peer; duplicate IDs are acknowledged but played once. If no link is available, the bounded SQLite outbox sends pending frames after reconnection. Received alerts retain one bounded store-forward copy for a later peer contact.

The IP field remains only for laptop diagnostics. Android cannot deliver to arbitrary phones: the receiving device must have this app installed, permissions granted, and Wi-Fi/BT receive mode enabled. The pairing phrase provides application-layer AES-GCM authentication, but it is not a fully provisioned public-key identity system. Bluetooth currently uses RFCOMM stream sockets; BLE GATT fragmentation remains necessary for BLE-only embedded devices.

## One-phone plus laptop transport test

Connect the laptop and phone to the same offline hotspot/Wi-Fi, then run:

```powershell
.\.venv\Scripts\python.exe .\scripts\sutra_laptop_peer.py --speak
```

Leave the pairing phrase blank and tap **Secure session** to disable encryption for this diagnostic peer. Enter the preferred laptop IPv4 address in the app's IP field and tap **Manual connect**. Speech recognized on the phone is decoded and printed by the laptop. It returns `RECEIVED_ACK`, speaks through the real Windows SAPI voice, and returns truthful `PLAYBACK_STARTED`/`PLAYBACK_FINISHED` events. Duplicate message IDs are acknowledged but never replayed, and the server waits for reconnection after a phone disconnect. Windows SAPI is a test harness, not the final reproducible open-source Android TTS implementation.

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

Tap **Export metrics**, then retrieve and summarize the CSV:

```powershell
.\.android-sdk\platform-tools\adb.exe pull /sdcard/Android/data/org.sih.offlinespeech/files/metrics/sutra_metrics.csv .\artifacts\sutra_metrics.csv
.\.venv\Scripts\python.exe .\scripts\sutra_metrics_report.py .\artifacts\sutra_metrics.csv
```

The report provides P50/P95/max sender-observed receive/playback-ACK latency. This includes return-ACK delay and is not a clock-synchronized one-way measurement.

## Validation boundary

The pinned VAD model contract, ONNX frontends, model bundle sizes, Whisper English smoke test, streaming resampler, SUTRA codec/CRC checks, AES-GCM round trips/wrong-key rejection, native-script critical-field detection, single/multi-peer TCP exchange, Kotlin compilation, APK installation, model preservation, foreground-service survival after backgrounding, and durable database creation are checked. Wi-Fi Direct DNS-SD, Bluetooth RFCOMM exchange, cross-phone TTS voice coverage, alert behavior, relay delivery, thermals and P50/P95 radio measurements still require the two-phone run above.
