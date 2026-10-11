# SUTRA: offline multilingual voice relay

Speech in, speech out, with no network of any kind.

SUTRA recognises speech on the sending phone, transmits the resulting **text** over a direct
Wi-Fi Direct or Bluetooth link in roughly 130 bytes, and re-synthesises it as speech on the
receiving phone. Ten languages: Hindi, Bengali, Gujarati, Kannada, Malayalam, Marathi, Odia,
Tamil, Telugu and English. No hosted API, no cell tower, no internet at any point in the loop.

The premise is that moving audio across a degraded link is the wrong problem. A spoken sentence
carried as text costs a measured median of 132 bytes on the wire, which is 40 to 80 times smaller
than the same utterance in AMR-WB and more than 800 times smaller than raw PCM. That reduction is
what lets the system keep working on a link that cannot sustain a voice call.

```text
microphone -> Silero VAD endpointing -> language identification -> offline recognition
           -> typed binary envelope -> AES-GCM seal
           -> Wi-Fi Direct TCP, with Bluetooth RFCOMM as fallback
           -> validation and de-duplication -> offline speech synthesis -> playback
           -> RECEIVED_ACK / PLAYBACK_STARTED / PLAYBACK_FINISHED
```

Two design decisions distinguish it from a text-over-mesh messenger. First, a transcript is
treated as uncertain evidence rather than fact: per-word recogniser confidence travels in the wire
format, risk-bearing tokens are typed, and a low-confidence critical field triggers a request to
repeat that field rather than the whole message. Second, every frame is independently sealed and
independently decryptable, so a message survives the link dying and being replaced by a different
radio.

---

## Measured results

Every figure below comes from a file in this repository. Sample sizes and conditions are stated
because they change how much each number is worth.

### On device

OPPO CPH2717, Android 16, 7.3 GB RAM, app 0.13. Pulled live from the on-device metrics database
after 16 app launches and 173 recorded utterances
(`evaluation/device_metrics_oppo_cph2717.json`).

| Metric | P50 | P95 | Mean | N |
|---|---:|---:|---:|---:|
| Speech end to recognition final | 1,692 ms | 5,568 ms | 1,923 ms | 173 |
| Recognition inference alone | 1,674 ms | 5,551 ms | 1,895 ms | 173 |
| Real-time factor | 0.62 | 3.07 | 0.98 | 173 |
| Receive to first synthesised audio | 902 ms | 1,958 ms | 1,105 ms | 133 |
| Sealed bytes per message | 132 B | - | 147 B | 343 |

Recognition inference ranged from 266 ms to 6,779 ms; sealed frames ranged from 98 B to 862 B.
The P95 tail is dominated by long utterances, which is the lever the adaptive endpointing and the
low-tier segment cap address.

On-device storage:

| Component | Size |
|---|---:|
| Indic recognition model | 647 MB |
| English recognition model (Whisper-tiny) | 123 MB |
| Speech synthesis, English/Hindi pair as staged for this run | 85 MB |
| Voice activity detector | 2.2 MB |
| Models total | 857 MB |
| APK, arm64-v8a, model-free | 21.4 MB |

One qualification that matters: this device run staged the older 647 MB Indic export, not the
864 MB `matmul_int8` artifact that the model-tier analysis below selects. The accuracy figures and
the on-device latency figures therefore come from different builds, and restaging the phone with
the selected artifact is outstanding work.

### Recognition accuracy

Shared 628M Indic encoder, MatMul-INT8, FLEURS test split, 30 clips per language, CPU at 4
threads (`wer_comparison_results.json`, `docs/model-tier-decision.md`).

| Language | WER | Language | WER |
|---|---:|---|---:|
| Hindi | 11.04% | Telugu | 22.26% |
| Gujarati | 15.06% | Odia | 17.21% |
| Marathi | 18.27% | Bengali | 15.98% |
| Kannada | 16.90% | Tamil | 34.24% |
| Malayalam | 18.12% | **Mean, 9 languages** | **18.79%** |

Odia at 17.21% is the strongest single result in the project and the hardest language in the set.

The full routed pipeline, including English and including language-identification errors, was
measured separately over 1,000 clips (100 per language, 3.43 audio-hours) in
`evaluation/results_fleurs_test_100/REPORT.md`:

| Metric | Result |
|---|---:|
| Final language accuracy | 99.40% (95% CI 98.70-99.72) |
| Coarse English/Indic accuracy | 100.00% |
| Macro routed WER | 20.97% |
| Macro oracle WER | 20.69% |
| Macro routed CER | 7.87% |
| English WER | 13.13% |
| Aggregate real-time factor | 0.045 |

Routing costs 0.28 points of macro WER, the gap between routed and oracle. That is the price of
choosing the model automatically rather than being told the language.

### Quantisation and the model tier

Four configurations measured identically, same clips, same decoding, same normalisation
(`docs/model-tier-decision.md`).

| Configuration | Mean WER | RTF | Disk | Languages covered |
|---|---:|---:|---:|---|
| Shared 628M, FP32 | 18.96% | 0.0997 | 2,381 MB | 22, shared |
| **Shared 628M, MatMul-INT8** | **18.79%** | **0.0228** | **864 MB** | 22, shared |
| Monolingual 120M, FP32 | 19.24% | 0.0510 | 460 MB | one |
| Monolingual 120M, MatMul-INT8 | 19.63% | **0.0198** | **178 MB** | one |

Quantising the shared model is effectively free: 2.76x smaller, 4.4x faster, and mean WER moves
from 18.96% to 18.79%. Per-language deltas run from -0.83 to +0.31 points and a paired bootstrap
finds **no language significantly different at 95%**.

The usual warning that dynamic INT8 runs about 3x slower does not apply here because quantisation
is restricted to MatMul. A Conformer encoder is MatMul-dominated, 289 MatMul against 77 Conv, so
the export produces 217 MatMulInteger and zero ConvInteger operators: it loads cleanly on the
ONNX Runtime CPU provider and runs faster, rather than falling back to an absent ConvInteger
kernel.

Tier selection is by how many languages a unit needs offline, not by phone class. Crossover is
864 / 178 = **4.9 languages**. One or two languages ship the monolingual export at 178-356 MB;
five or more, or an unknown deployment, ship the shared model at 864 MB, which is both cheaper
than five separate models and more accurate.

Two pre-existing exports were measured and rejected. `hybrid_int8` is 218 MB smaller with an
identical operator graph, but 1.74 points worse on average, **8.13 points worse on Odia**, 2.5x
slower and 440 MB heavier in memory. The difference is scale granularity: the selected export uses
per-channel scales.

### Memory

| Configuration | Peak | Note |
|---|---:|---|
| Shared INT8, ONNX Runtime only | 999 MB | corrected figure |
| Monolingual INT8, ONNX Runtime only | 438 MB | corrected figure |
| Monolingual INT8, low-tier session options | 352 MB | arena and memory planner disabled |

The 1,676 MB and 1,102 MB peaks that appear in the raw benchmark JSON are harness artefacts: the
comparison script imports PyTorch and librosa, and `import torch` alone accounts for +498 MB that
an Android build never loads. The corrected figures above were taken with ONNX Runtime only, which
is what the app actually loads.

On devices classified low tier, under 3 GB RAM or four cores or fewer, disabling the CPU arena and
the memory-pattern planner moves peak resident memory from 438 MB to 352 MB, a 20% reduction, at a
cost of 1.00 s to 1.33 s on a 20 s utterance. With a real-time factor near 0.02 there is roughly
50x of speed headroom to spend and none to spare on a phone that runs out of memory. Thread count
is not a memory lever: one intra-op thread instead of four saved 1 MB and doubled inference time,
so threads are left alone.

Activation memory scales with segment length and steepens past 15 s: 24.6 MB at 4 s, 52.0 MB at
8 s, 81.0 MB at 15 s, 145.0 MB at 20 s, 214.2 MB at 30 s. Low-tier devices therefore cap a segment
at 8 s rather than 15 s, bounding the worst case at the cost of splitting a very long monologue
into two messages.

### Channel robustness

Synthetic degradations applied to FLEURS read speech, 30 clips per language, shared INT8 model
(`evaluation/channel_robustness.json`, harness `scripts/channel_robustness.py`). WER, percent.

| Condition | Hindi | Odia | Tamil |
|---|---:|---:|---:|
| Clean | 11.04 | 17.21 | 34.24 |
| White noise, 20 dB | 10.55 | 19.54 | 32.97 |
| White noise, 10 dB | 13.15 | 27.66 | 36.61 |
| White noise, 5 dB | 16.00 | 38.88 | 44.44 |
| White noise, 0 dB | 22.46 | 64.41 | 49.91 |
| Four-talker babble, 10 dB | 16.38 | 49.90 | 28.05 |
| Four-talker babble, 5 dB | 33.50 | 71.95 | 34.61 |
| Narrowband 300-3400 Hz | 10.79 | 18.96 | 36.25 |
| Packet dropout, 5% | 11.54 | 18.18 | 31.51 |
| Packet dropout, 10% | 11.04 | 18.96 | 30.60 |
| Clipping at 30% of peak | 10.55 | 18.38 | 26.05 |

The useful readings: narrowband radio-band filtering, 10% lost audio windows and a hot clipping
microphone are all close to free, which is what matters for a field radio. Babble is the real
enemy, and Odia degrades far faster than Hindi under it. No condition produced an empty
hypothesis. These are synthetic degradations on read speech; they bound the direction and rough
scale of field loss and are not a field recording.

### Language identification

Frozen 256-dimensional VoxLingua ECAPA embedding plus a 5,589-byte logistic head. A candidate
scoring at least 0.30 for English is verified against Whisper-tiny's English language-token
probability and routes to English only if that also clears 0.30
(`evaluation/LID_ROBUST_ROUTER_REPORT.md`).

| Test | Legacy route | Head alone | Head plus verifier |
|---|---:|---:|---:|
| FLEURS, 100 clips x 10 languages | 100.0% | 99.8% | 100.0% |
| FLEURS Odia | 100.0% | 100.0% | 100.0% |
| Code-switched, Hindi-majority routed Indic | 99.92% | 62.97% | 97.55% |
| Code-switched, English-majority routed English | 0.24% | 67.56% | 22.44% |
| Code-switched, dominant-language agreement | 86.01% | 63.61% | 87.07% |

Training used 900 Indian-English clips and 900 FLEURS Indic clips; validation reached 99.26%
balanced accuracy over 540 clips, and a pure Indian-English test scored 247/250. There are 1,337
speakers with zero overlap across train, validation and test, and every speaker in the 1,000-clip
benchmark was excluded beforehand.

The honest reading of the fourth row: Indian-accented and code-switched English remains the
weakest point in the system. The verifier lifts English-majority routing from 0.24% to 22.44%,
which is a large relative improvement and still not good. Code-switched word ratios are a routing
diagnostic rather than utterance-level ground truth, so no single Hinglish accuracy is claimed.

### Wire efficiency

| Payload | Size |
|---|---:|
| Fixed envelope header | 44 B |
| Trailing CRC-32 | 4 B |
| Representative 150-character transcript, sealed | 198 B |
| Compact alert frame | 72 B |
| Measured median on device | 132 B |
| Measured maximum on device | 862 B |

---

## Architecture

### Recognition

16 kHz mono capture with sample-rate fallback and resampling feeds a Silero VAD endpointer
running as a 576-sample ONNX graph with a persistent 128-unit LSTM state. The detector holds its
state and a 64-sample rolling context across utterance boundaries, so a second sentence is not met
by a cold-start detector, and a pre-roll ring buffer prepends audio captured before the threshold
crossing so a leading consonant is not clipped.

A confirmed pause emits the segment to the recogniser. Coarse routing sends English to
Whisper-tiny and everything else to the shared IndicConformer CTC encoder, which runs one encoder
pass followed by nine cheap per-language mask decodes and returns the winning transcription. Model
sessions are warmed during startup on the worker threads that later use them, and model load time
is reported separately from inference so it is excluded from the real-time factor.

### Protocol

A fixed 44-byte binary header carries magic bytes, protocol version, message type, flags, a
one-byte language code, header length, a 128-bit random message identifier, 64-bit source and
destination terminal identifiers and the payload length, followed by optional typed extensions, a
UTF-8 payload and a CRC-32. There is no JSON anywhere on the wire.

One utterance can become up to three messages sharing one identifier, ordered so that the
actionable part arrives first: `ALERT_CORE`, then `TEXT_FINAL`, then `REPAIR_EVIDENCE`. Message
types cover session negotiation, final text, alerts, received and playback acknowledgements,
repair requests and the optional relay extension.

The sender stamps the detected language into the header, so the receiver never re-runs language
identification. It reads one byte and dispatches straight to the matching voice, which removes an
entire model invocation from the critical path.

### Critical fields and repair

Risk-bearing tokens are typed as `NUMBER`, `NEGATION`, `EMERGENCY` or `LOCATION_HINT` against
native-script lexicons for all ten languages, with no transliteration round-trip and no English
pivot. Per-word recogniser confidence is attached to each field. Any critical field below the 0.72
threshold is emitted as a separate repair-evidence layer and flagged in the header, letting the
receiver request repetition of exactly the uncertain tokens rather than the whole message.

The asymmetry this encodes is that not all words carry equal risk. A wrong adjective is noise; a
wrong negation, count or floor number inverts the message.

### Transport

A length-prefixed TCP stream over an infrastructure-free Wi-Fi Direct group is the primary path,
with paired Bluetooth RFCOMM as a genuine fallback sharing the same frames and the same durable
outbox. Failover is driven by a live predicate on whether Wi-Fi is actually carrying data, not by
whether a group merely exists, so a Wi-Fi Direct group whose TCP link never came up no longer
keeps Bluetooth stood down. This is failover, not bonded multipath.

Sending costs no connection setup. Frames are written into already-open sockets behind a per-peer
write lock; there is no connect, no handshake and no key exchange on the send path. The marginal
cost of a pause while speaking is one recognition pass and a socket write.

### Security

Security is per frame rather than per session. A pairing phrase is stretched once at service start
through PBKDF2-HMAC-SHA256 at 60,000 iterations into a 256-bit master key held in memory. Each
frame then carries a fresh 16-byte salt and 12-byte nonce, derives a single-use key by HKDF, and
is sealed with AES-GCM using the version prefix and salt as associated data. Unknown versions,
oversized lengths, bad authentication tags and replayed message identifiers are all rejected
before allocation or playback.

The consequence is zero cryptographic round trips on reconnect, and a message that can be sealed
into the outbox before any link exists and delivered later over whichever radio returns. The
trade-off is no forward secrecy, and the shipped default phrase is a demo key present in the
binary, not a secret. Peers are authenticated only by possession of the phrase.

### Speech synthesis

Synthesis is the AI4Bharat Indic-TTS stack run as two ONNX graphs in series, a FastPitch acoustic
model and a HiFi-GAN vocoder. The INT8 bundle carries nine FastPitch models covering all ten
languages (`en-hi`, `bn`, `gu`, `kn`, `ml`, `mr`, `or`, `ta`, `te`) against one shared vocoder, at
22,050 Hz with a default speaker identifier of 0.

| Payload | Size |
|---|---:|
| One FastPitch acoustic model | 62.7 MB |
| Shared HiFi-GAN vocoder | 55.8 MB |
| One language installed | about 119 MB |
| All nine installed | about 591 MB |

Voices are staged outside the APK and installed per language, so a unit provisioned for one
language does not carry the other eight. The loader keeps a single FastPitch session resident and
closes the previous one when the language changes; the vocoder stays resident because every call
needs it. Language availability is resolved from what is actually staged rather than hard-coded,
so an unstaged language falls through to the system-voice path.

Text is NFC-normalised, filtered to the model vocabulary and split on both Latin and Devanagari
punctuation, then wrapped at roughly 70 characters on word boundaries.

Playback streams rather than batches. Only the first chunk is synthesised before a float-PCM
`AudioTrack` opens and starts playing; the remaining chunks are synthesised while the first is
still audible, separated by a 60 ms gap so clause boundaries sound natural. Completion is verified
against the hardware playback head position with a duration-derived deadline, so
`PLAYBACK_FINISHED` reports a measured fact.

The FastPitch graph was patched to expose `pitch_bias` and `duration_scale` inputs at named
internal tensors, and a calibration pass fitted the bias-to-F0 mapping monotonically over real
Hindi distress phrases. An operator can therefore select one word in a received message and replay
it with a calibrated +25 Hz emphasis, entirely offline. The app detects at load time whether the
installed graph supports these inputs and degrades to neutral playback with an explicit notice.

Any language whose voice is not staged falls back to Android's offline synthesis engine, filtered
to voices that do not require a network connection and preferring the regional variant. Alerts are
routed
through the alarm stream so they survive Do Not Disturb, and every playback raises a duplex gate
that mutes the local recogniser for its duration plus a 750 ms drain, so synthesised speech is not
recaptured and re-transcribed.

### Team map

Each phone publishes a stable terminal identifier and optional position every 10 seconds over
existing links. Position sharing is opt-in, foreground-only, with no background service and no
stored history. Distance between peers is reported as an interval bounded by both reported
accuracy radii, with direction explicitly unresolved when the halos overlap, and fix age is
computed on monotonic clocks without ever subtracting a remote wall clock. Alerts without a fresh
fix appear as unverified-location reports rather than guessed pins. Relay is bounded at four
forwarding steps with a 2,048-entry receipt set for loop suppression, at most 128 peers and a
10-minute expiry.

---

## Repository layout

```text
sih_speech/          reusable Python pipeline package
scripts/             microphone, dataset, benchmark, quantisation and measurement commands
tests/               fast offline Python regression checks
tools/               ONNX repair and model inspection utilities
models/              downloaded language-ID, Whisper, VAD and synthesis models
deployment_models/   validated clean runtime trees, including indic/matmul_int8
evaluation/          pinned manifests, audio subsets and benchmark reports
artifacts/           recordings, calibration output and intermediate exports
docs/                transmission protocol, model tier decision, team map, metrics card
android/             primary Kotlin and ONNX Runtime app with staged models
android-bundled/     self-contained APK variant; generated models stay untracked
landing/             single-file presentation page with per-number provenance
showcase/, video/    additional presentation surfaces
```

The downloaded Indic model directories and completed evaluation artifacts deliberately keep their
existing paths so earlier reports stay reproducible. APKs and model weights are untracked.

---

## Build and run

### Python pipeline

```powershell
py -3.10 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Install `requirements-training.txt` only when rebuilding the language-ID head.

```powershell
# Live microphone
.\.venv\Scripts\python.exe -m scripts.live_mic --lid-head artifacts/lid/indian_english_head.pt --whisper-lid-threshold 0.3

# Language ID only
.\.venv\Scripts\python.exe -m scripts.live_mic --lid-only

# Transcribe a file
.\.venv\Scripts\python.exe infer_file.py ".\artifacts\recordings\last_mic.wav" --lid-head artifacts/lid/indian_english_head.pt --whisper-lid-threshold 0.3

# Fast offline checks
.\.venv\Scripts\python.exe -m tests.test_routing
.\.venv\Scripts\python.exe -m tests.test_metrics
```

### Android app

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline
```

Use the Android Studio JBR as `JAVA_HOME`. Thirteen unit-test classes cover the VAD endpoint
policy, the audio pipeline, the wire codec and CRC, AES-GCM round trips and wrong-key rejection,
native-script critical-field detection, telemetry arithmetic, the synthesis tokeniser and chunker,
TCP transport, conversation state, automatic peer selection, Bluetooth distance estimation, the
translator, map distance and team-position relay.

Model staging, ADB installation, routing controls and on-device capture are documented in
[`android/README.md`](android/README.md).

---

## Reproducing the measurements

```powershell
# 1,000-clip routed benchmark
.\.venv\Scripts\python.exe -m scripts.benchmark

# Four-configuration model tier comparison
.\.venv\Scripts\python.exe -m scripts.compare_mono_vs_multi

# Channel robustness sweep
.\.venv\Scripts\python.exe -m scripts.channel_robustness

# Rebuild the speaker-disjoint language-ID head
.\.venv\Scripts\python.exe -m scripts.prepare_lid_pilot
.\.venv\Scripts\python.exe -m scripts.train_lid_head
.\.venv\Scripts\python.exe -m scripts.evaluate_lid_head

# Clean runtime packaging
.\.venv\Scripts\python.exe -m scripts.build_runtime_models

# Prosody graph patch and calibration
.\.venv\Scripts\python.exe -m scripts.patch_fastpitch_prosody_onnx
.\.venv\Scripts\python.exe -m scripts.calibrate_fastpitch_pitch
```

On-device capture uses `scripts/measure_on_device.sh`, which pulls the metrics CSV, `dumpsys
meminfo` and logcat off an attached phone and prints medians, and
`scripts/sutra_metrics_report.py`, which summarises a pulled database.
`scripts/sutra_laptop_peer.py` is a diagnostic peer that speaks the same protocol from a laptop; it
is a test harness, not part of the deliverable.

Text normalisation for every accuracy figure is Unicode NFC, case-folding, punctuation and symbols
replaced by spaces, whitespace collapsed. Indic combining marks are preserved. WER is computed over
whitespace tokens at corpus level from total edit counts, not as a mean of per-utterance
percentages. CER excludes spaces. Real-time factor is total inference seconds over total audio
seconds and excludes model load and audio decode.

---

## Verification status

| Area | Status |
|---|---|
| Recognition accuracy, 9 Indic languages plus English | Measured, 1,000 clips and 30 clips per language |
| Language identification accuracy | Measured |
| Model size, quantisation and tier decision | Measured, four configurations |
| Channel robustness | Measured, synthetic degradations on read speech |
| Single-device latency and real-time factor | Measured on device, 173 utterances |
| Wire bytes per message | Measured on device, 343 frames |
| Peak memory | Measured with ONNX Runtime on desktop; on-device PSS needs a re-read |
| Speech synthesis, all ten languages | Neural voices exist for all ten; one phone run measured with the English/Hindi pair only |
| Speech synthesis peak memory on a phone | Not measured for the multi-voice bundle |
| Protocol, crypto, repair loop, relay | Implemented and unit-tested |
| Two-phone radio exchange over Wi-Fi Direct and Bluetooth | Not measured |
| Cross-device end-to-end latency | Instrumented with clock-offset exchange; no paired reading yet |
| Multi-hop relay, thermals, sustained battery | Not measured |

---

## Models, data and licences

| Component | Source | Licence |
|---|---|---|
| Indic recognition | AI4Bharat IndicConformer 600M multilingual, quantised locally | MIT |
| English recognition | Whisper-tiny | MIT |
| Voice activity detection | Silero VAD | MIT |
| Language-ID embedding | SpeechBrain VoxLingua107 ECAPA | see upstream model card |
| Speech synthesis | AI4Bharat Indic-TTS FastPitch and HiFi-GAN, nine voices plus shared vocoder | see upstream model card |
| Accuracy corpus | `google/fleurs`, pinned at `70bb2e84b976b7e960aa89f1c648e09c59f894dd` | CC-BY-4.0 |
| Indian-English training clips | `ishands/commonvoice-indian_accent` | CC0-1.0 |
| Code-switch routing diagnostic | MUCS-Hinglish | see upstream dataset terms |
| Map tiles, optional online view only | OpenStreetMap, attributed in-app | ODbL |

No hosted inference API is used anywhere. The shared Indic encoder carries vocabulary masks for 22
languages; nine are evaluated and shipped. This repository does not yet carry a `LICENSE` file of
its own, which should be added before distribution.

---

## Limitations

These are stated plainly because the numbers above are only worth what their conditions allow.

- **FLEURS is clean read speech.** Every accuracy figure is optimistic relative to field
  conditions. Kathbath or IndicVoices audio would be a harder and more representative benchmark
  and neither is present in this project.
- **30 clips per language gives plus or minus 2 to 4 points.** That is adequate for a go or no-go
  decision and not enough to resolve a genuine 1 to 2 point regression. Gujarati and Odia show the
  largest tier gaps with confidence lower bounds just below zero and should be re-checked at 100
  or more clips.
- **The device run and the accuracy run used different Indic exports.** The phone staged the
  647 MB export; the analysis selects the 864 MB one.
- **The warm on-device memory reading needs a re-read.** The captured figure reports 1,819 MB PSS
  against a 159 MB resident set with 1,764 MB attributed to compressed swap, which is not a
  plausible working-set reading and should be re-measured before it is quoted.
- **Channel degradations are synthetic.** They bound the direction and rough scale of field loss.
  They are not field recordings.
- **Indian-accented and code-switched English routing is weak.** 22.44% on English-majority
  code-switched clips is the known floor of the system.
- **Ten-language synthesis exists but is measured on two.** Neural voices are available for all
  ten languages and the loader selects among them, but the only on-device run used the
  English/Hindi pair. The INT8 FastPitch graphs contain ConvInteger and MatMulInteger operators
  and the vocoder is convolution-heavy and float-dominated, so INT8 in the filename does not mean
  the pipeline is 8-bit or that runtime memory matches file size. Per-language peak memory on a
  handset is unmeasured.
- **No two-phone radio measurement exists.** Discovery, failover, the repair loop and relay
  compile and pass protocol and loopback tests, but no paired-handset timing has been captured.
- **Security is link-grade, not field-grade.** No forward secrecy, no peer identity, and a default
  phrase that ships in the binary.
- **Positions are peer-reported.** The team map is not a spoof-resistant tracking system, and
  reported accuracy radii are 68th-percentile estimates rather than guaranteed bounds.

---

## Further reading

- [`docs/TRANSMISSION_PIPELINE.md`](docs/TRANSMISSION_PIPELINE.md) - wire format, framing per
  transport, routing, security and the measurement protocol
- [`docs/model-tier-decision.md`](docs/model-tier-decision.md) - the four-configuration comparison
  and the tier rule
- [`docs/TEAM_MAP.md`](docs/TEAM_MAP.md) - position relay, uncertainty handling and the hardware
  boundary
- [`evaluation/release_benchmark_20260903/REPORT.md`](evaluation/release_benchmark_20260903/REPORT.md) -
  full release benchmark including rejected variants
- [`evaluation/LID_ROBUST_ROUTER_REPORT.md`](evaluation/LID_ROBUST_ROUTER_REPORT.md) - language-ID
  router pilot and held-out results
- [`android/README.md`](android/README.md) - staging, installation, controls and on-device capture
- [`landing/README.md`](landing/README.md) - every presentation claim mapped to its source file
