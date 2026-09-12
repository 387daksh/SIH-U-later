# Android offline test app

This is a native Kotlin microphone UI using ONNX Runtime Android. It integrates Silero VAD for real-time pause and silence detection, segmenting continuous speech into discrete utterances passed immediately to the offline STT pipeline (Whisper for English and 9-language IndicConformer vote). Model files are deliberately staged outside the APK to keep the app lightweight and fast to iterate. English/Hindi playback uses the quantized AI4Bharat Indic-TTS FastPitch + HiFi-GAN pair when installed; the other eight languages currently fall back to an installed offline Android voice.

It also contains the SUTRA transmission prototype: a compact binary multilingual envelope, app-specific Wi-Fi Direct discovery, paired-phone Bluetooth RFCOMM fallback, a durable outbox and receipt ledger, AES-GCM authenticated sessions, foreground receiving, automatic receiver TTS, bounded alert relay, metrics export, and received/playback semantic acknowledgements.

## Prepare the models

### Visual design (0.11)

The UI uses a shared warm-paper, navy and teal palette across Talk, Devices and
language selection. Talk has an illustrated empty state, softer message bubbles,
translation dividers, compact replay actions and a rounded recording dock. The ring
around the microphone moves only during active recording; motion follows the system
animation setting. New message cards and tab changes use short fade transitions.

Emergency mode has a persistent banner across tabs. **Cancel** discards any active
recording and exits emergency mode. Switching away from Talk cancels an active held
or tap recording too. Message-mode controls are locked while recording.

Devices shows initial avatars and a **Name** action. Nicknames are saved locally per
transport address, up to 40 characters; they are not broadcast identities or a claim
that Wi-Fi and Bluetooth entries are the same phone. Routine scan updates preserve
the displayed rows when names and connection states have not changed.

### Conversation UI (0.10)

Talk now shows message cards above fixed recording controls. Each card keeps its
original language, optional translation, Replay/Copy actions, and its own delivery
state. **Heard** means a `PLAYBACK_FINISHED` acknowledgement was received; the count
is distinct acknowledging source IDs for that message, not a claim that all nearby
phones or humans heard it. Duplicate and delayed receipts cannot move progress backwards.
Incoming playback errors retain the text and offer replay. ONNX playback must fully
drain before completion is acknowledged.

Hold to record and release to send, or use **Tap to record** and **Send recording**.
Moving outside the held button, choosing **Cancel recording**, or leaving the activity
cancels that recording. The recording pulse respects disabled system animations.
Emergency mode requires confirmation and resets after a successfully submitted message.
Hindi message cards offer **Emphasize a word**; select a word, then Replay. Once selected,
words in the transcript can be tapped to change the selection. This remains local replay
pitch control using the existing calibration, not STT-derived alignment.

Devices shows searching, connecting, permission-needed and ready states. Search/retry
appears when action is needed. Radio link counts and disconnect controls are in
**Show advanced connection tools**. Link counts are not unique phone counts.
The latest 32 message cards survive activity recreation while the foreground service
lives; they are session history, not a persistent chat archive. The durable outbox is unchanged.

### Automatic peer connections (0.9)

Open SUTRA on every phone and allow Nearby devices. Discovery and reconnection run in
the foreground service, including while another app screen is open. Wi-Fi DNS-SD
identifies SUTRA instances, prefers an existing group owner, and elects one initiator
when idle phones meet. The owner accepts multiple TCP clients. Bluetooth scans nearby
and paired devices, confirms the SUTRA service UUID with SDP, and connects automatically;
it keeps its listener and existing peer streams open while adding another peer.
Normal Android connection/pairing prompts still require approval. New Bluetooth phones
must allow the discoverability prompt (up to five minutes); use **Make this phone visible** to renew
that window. Radio/hotspot limitations and hardware connection limits still apply.

Diagnostics shows Wi-Fi and Bluetooth **link counts**, which can include two links to
the same phone. **Pause & disconnect** pauses automatic connections on both transports;
**Resume automatic connections** resumes them. **Search again** starts another discovery cycle.
Manual connect controls are under **Show advanced connection tools**. The Devices list
distinguishes discovered Wi-Fi devices, Bluetooth candidates, and connected Bluetooth peers.
Messages use the existing receipt deduplication and live relay across active links.
These changes require the updated APK on all test phones. Local socket/policy tests
do not substitute for a three-phone radio test.

From the repository root:

```powershell
# Download the notebook output and extract it once:
Expand-Archive .\onnx_en+hi.zip .\models\tts\en-hi -Force

.\.venv\Scripts\python.exe .\scripts\stage_android_models.py
```

The generated bundle remains untracked under `artifacts/android_models`. It is 771.70 MiB without TTS and roughly 856.5 MiB with the selected `en+hi` pair. It includes:
- **Silero VAD ONNX v6.2.1**: Pinned by SHA-256 and used for real-time pause segmentation.
- **Whisper (English)**: Hybrid FP32 encoder + INT8 decoder.
- **IndicConformer (9 Indian Languages)**: Hybrid INT8 model with 9-language CTC decoding.
- **Indic-TTS `en+hi`**: `fastpitch_en+hi.prosody.onnx` + `hifigan_en+hi.minmax.onnx`, staged under stable `tts/en-hi` names with `meta.json`.

Only those two selected ONNX graphs are staged; the 422 MB notebook export also contains fp32 and rejected/alternative quantizations that the app does not need. The selected pair is about 88.9 MB. If `models/tts/en-hi` is absent, staging prints a warning and the APK keeps the system-voice fallback.

To add TTS to a phone that already has the 800 MB model installation, push only the selected files into the same external model directory:

```powershell
$adb = ".\.android-sdk\platform-tools\adb.exe"
$remote = "/sdcard/Android/data/org.sih.offlinespeech/files/models/tts/en-hi"
& $adb shell mkdir -p $remote
& $adb push ".\models\tts\en-hi\fastpitch_en+hi.prosody.onnx" "$remote/fastpitch.onnx"
& $adb push ".\models\tts\en-hi\hifigan_en+hi.minmax.onnx" "$remote/hifigan.onnx"
& $adb push ".\models\tts\en-hi\meta.json" "$remote/meta.json"
```

Restart the app after copying. No STT model is downloaded or copied again.

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

Grant microphone permission and hold **Hold to talk**, then release to send. Alternatively tap **Hands-free** and speak with pauses between sentences. Routing defaults to AUTO; its manual override is in the advanced connection tools. Choose **Emphasize a word** on a Hindi message, then **Replay** to apply the calibrated +25 Hz pitch bias. Choose **Neutral replay** to clear emphasis. AI4Bharat Indic-TTS is used for English/Hindi when staged; otherwise the installed offline system voice is used.

## Two-phone SUTRA test

Install the same APK and model bundle on both phones. No router, hotspot, Internet connection, or IP address is required: Android Wi-Fi Direct creates the local link.

1. Enable Wi-Fi and Bluetooth on both phones and grant **Nearby devices** and notification permissions.
2. If you changed the shared pairing phrase, use **Show advanced connection tools** to enter the same phrase on both phones and tap **Secure session**. Different phrases are rejected by AES-GCM authentication.
3. Open **Devices** on both phones. Discovery starts automatically; use **Search again** to retry.
4. Accept Android's Wi-Fi Direct invitation or Bluetooth pairing/visibility prompts if shown. Wait for an active link count; a discovered candidate alone does not prove a connection.
5. Leave **Normal message** selected, hold the microphone, speak, and release. The receiver displays and speaks the text; the sender's delivery indicators advance with receipt and playback acknowledgements.
6. Select **Emergency alert** and speak a sentence containing a negation, number, emergency term, or location hint. `ALERT_CORE` is sent before the transcript and the receiver requests alarm audio focus.
7. Background the receiver. Its foreground `connectedDevice|mediaPlayback` service keeps the socket, outbox, deduplication and TTS alive.

Bluetooth discovery and connection also run automatically. Use **Make this phone visible** on a new phone and approve Android's pairing prompt. Fresh broadcast transcripts are relayed once across the middle phone's active Wi-Fi/Bluetooth links; duplicate IDs are acknowledged but played once. If no link is available, the bounded SQLite outbox sends pending frames after reconnection. Received alerts retain one bounded store-forward copy for a later peer contact.

### Bluetooth proximity map

Expand **Show signal map** in **Devices** for the local Bluetooth signal view. The app smooths discovery RSSI and converts it to a rough distance with a log-distance path-loss model (`-59 dBm` at one metre, path-loss exponent `2.2`). Directions are illustrative, not measured positions. Manual **Scan range** and peer selection remain under **Show advanced connection tools**.

RSSI ranging is intentionally labelled approximate. Walls, people, pockets, device antennas and radio power can change the result substantially. For a demo venue, measure RSSI at one metre with the actual phone pair and adjust `DEFAULT_TX_POWER_AT_ONE_METRE` in `BluetoothDistanceEstimator.kt`; adjust the path-loss exponent for the room only if you have measurements at several known distances. Classic Bluetooth discovery does not provide continuous RSSI on an active RFCOMM stream, so scanning is disabled while that data connection is active to protect message delivery.

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

The pinned VAD model contract, ONNX frontends, model bundle sizes, Whisper English smoke test, streaming resampler, SUTRA codec/CRC checks, AES-GCM round trips/wrong-key rejection, native-script critical-field detection, single/multi-peer TCP exchange, Kotlin compilation, APK installation, model preservation, foreground-service survival after backgrounding, and durable database creation are checked. The Indic-TTS tokenizer/chunker is unit-tested, but its ONNX pair is not present in this checkout, so phone synthesis remains unverified until the notebook output is staged. Wi-Fi Direct DNS-SD, Bluetooth RFCOMM exchange, cross-phone TTS coverage, alert behavior, relay delivery, thermals and P50/P95 radio measurements still require the two-phone run above.
