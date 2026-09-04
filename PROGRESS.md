# Progress log — routing fix, calibration, and Android feasibility

This covers two connected pieces of work: (1) debugging and fixing a real
misrouting bug in the desktop pipeline, and (2) a from-scratch feasibility
test of running the same three-model pipeline on a physical Android phone.
Written to be self-contained — a future session (or teammate) should be able
to pick up from here without re-deriving anything.

---

## Part 1 — Desktop pipeline: the LID misrouting bug

### Symptom

Speaking English into `live_mic_test.py` sometimes produced a transcription
in Hindi, Marathi, or another Indic language instead of English.

### Root cause

`VoxLinguaCoarseLID`'s confidence is **duration-sensitive**: tested on the
same real English recording sliced to different lengths, its confidence in
the correct label dropped from 98% (13s) → 88% (3s) → 58% (2s) → 0.6% (1s,
effectively noise), and background noise made it worse at any length. The
old code trusted whatever the LID said unconditionally — so a short or noisy
real-mic utterance (exactly what a walkie-talkie app produces) could be a
near-coin-flip that got treated as a confident decision.

### First fix attempt (superseded — see calibration below)

Added a confidence threshold: below it, run **both** Whisper and
IndicConformer and pick whichever reports higher self-confidence in its own
output (`SpeechPipeline._cross_confidence_fallback` in
[`sih_speech/pipeline.py`](sih_speech/pipeline.py)). This fixed the
originally-reported short-clip English misroute.

### The fix itself had a bug — found before shipping it as default

Testing the fallback on a real recording ("मेरा नाम किंशुक अग्रवाल है...")
showed Whisper's confidence (0.58, on a fully hallucinated English sentence)
beating IndicConformer's confidence (0.19, on the *correct* Hindi
transcription). Whisper's autoregressive LM head is structurally
overconfident — it stays fluent-sounding even when the acoustic content is
wrong — while IndicConformer's CTC confidence runs on a stricter, lower
scale even when correct. The two numbers are not calibrated to the same
scale, so a raw `>=` comparison is biased toward Whisper by construction.

### Calibration — real data instead of another guess

Built `scripts/calibrate_routing.py`: collects LID + Whisper + IndicConformer
confidence on real, gold-labeled audio, then scores candidate routing rules
against ground truth. Ran it on **300 real FLEURS samples** (30 per
language × 10 languages, freshly downloaded — `evaluation/fleurs_calibration_30/`).
Results (`evaluation/routing_calibration/RESULTS.md`):

| Rule | Coarse routing accuracy |
|---|---:|
| **Trust LID's own pick, always** | **100.0%** |
| Old fix (LID confidence ≥0.6, else cross-model fallback) | 94.3% (17/300 misroutes) |
| Raw Whisper-vs-Indic confidence, no LID at all | 30.3% |

Trusting LID unconditionally beat the "smarter" fallback — the fallback was
second-guessing *correct* LID calls using an uncalibrated signal and losing
accuracy. Dropping LID entirely is worse than useless (30.3% is below the
90% you'd get by always guessing "indic" on this class-imbalanced set).

**Caveat, stated in code and here:** this calibration set is full
natural-length FLEURS sentences, not short push-to-talk clips — so it
doesn't re-test the original short-clip failure mode. `pipeline.py`'s
`lid_confidence_threshold` still exists (default `0.0`, i.e. always trust
LID) so the fallback path can be re-enabled and A/B tested, but it should
not be trusted as a fix without new calibration data for that regime.

### Current shipped behavior

`SpeechPipeline(..., use_lid=True, lid_confidence_threshold=0.0)` — LID
always decides English-vs-Indic; IndicConformer's own internal 9-way CTC
vote decides which specific Indic language (unaffected by this fix, and
never was — LID only ever gated the coarse English/Indic split, per the
original architecture). `use_lid=False` is kept for A/B comparison via
`--no-lid` on `live_mic_test.py` / `sih_pipeline.py`, clearly labeled
`no-lid-uncalibrated` in the returned `decision` field.

### Incidental environment/repo bugs found and fixed along the way

- `requirements.txt` pinned `onnxruntime-gpu` (no macOS wheel, CUDA-only) —
  contradicts the README's own "CPU-first" framing; `torchaudio` was missing
  entirely despite being a hard `speechbrain` dependency.
- Two parallel Python installs on the dev machine (3.11 vs 3.12) meant `pip`
  and `python3` resolved to different environments — a real footgun for
  anyone following the README's setup steps as-is on macOS/Linux (written
  Windows-only).
- `huggingface_hub`'s newer "Xet" transfer backend has a bug downloading the
  IndicConformer repo (`Unable to parse string as hex hash value`) —
  workaround is `HF_HUB_DISABLE_XET=1`.
- `models/indic` (the fp32 checkpoint) is ~30-60x slower per inference on
  this machine than the already-quantized `indic-conformer-600m-int8-onnx-patched`
  variant `benchmark.py` actually defaults to — a 9.7s clip's encoder pass
  went from 26s (fp32) to 4.3s (int8) after quantizing.

---

## Part 2 — Android feasibility test

**Goal:** find out whether the LID + IndicConformer + Whisper pipeline can
actually load and run on a real 8GB Android phone — not estimate it, run it.

### Model export & quantization

All three models now run purely through ONNX Runtime — no PyTorch/LibTorch
needed on-device.

| Model | fp32 | int8 (device) | Notes |
|---|---:|---:|---|
| IndicConformer (encoder + CTC decoder only, no RNNT) | 2.4GB | 632MB | quantized via `scripts/measure_footprints.py convert_indic` (existing tool, run for the first time this session) |
| VoxLingua LID | 86MB | 22MB | newly exported (`tools/export_lid_onnx.py`), then quantized |
| Whisper-tiny | — | 99MB | reused sherpa-onnx's prebuilt int8 export (`csukuangfj/sherpa-onnx-whisper-tiny`), not re-quantized ourselves |

**Total on-device: ~753MB weights** — comfortably fits an 8GB phone.

Two new export tools were written because neither model was fully ONNX to
start with:

- **`tools/export_indic_preprocessor_onnx.py`** — IndicConformer's
  mel-spectrogram preprocessor was a TorchScript module
  (`assets/preprocessor.ts`, loaded via `torch.jit.load`), not ONNX. Its
  `torch.stft(..., return_complex=True)` call isn't traceable by the ONNX
  exporter. Reimplemented as an equivalent Conv1d-based real-valued DFT
  (windowed cosine/sine kernels, stride=hop) and validated to match the
  original module to float32 noise (~1e-3 max abs diff) on real audio of
  varying length, including through the exported ONNX graph itself.
- **`tools/export_lid_onnx.py`** — same problem, in SpeechBrain's `Fbank`
  feature extractor this time (`speechbrain.processing.features.STFT`).
  Same fix (Conv1d DFT), validated to ~1e-7 max abs diff against the
  original. The whole LID pipeline (features → ECAPA-TDNN → restricted
  9-way classifier) is now one ONNX graph: raw waveform in, 9 scores out.
- **`tools/patch_convinteger.py`** (pre-existing, never previously run) —
  standard ONNX Runtime (not just mobile builds) doesn't implement
  `ConvInteger`, which `onnxruntime.quantization.quantize_dynamic` produces.
  Ran it on both the Indic and LID int8 exports.
- **Whisper's mel filterbank** — computed via `librosa.filters.mel(sr=16000,
  n_fft=400, n_mels=80)` (the same formula OpenAI Whisper's bundled filters
  use) and exported as a raw float32 binary consumed directly by the Kotlin
  code, avoiding a dependency on the `openai-whisper` package.

Before porting the Whisper pipeline (mel spectrogram → encoder → KV-cache
decoder loop → detokenize) to Kotlin, it was built and run **in Python
first** against the real sherpa-onnx ONNX files and cross-checked against
the existing HF `WhisperASR` output on the same audio — output matched
closely ("The group currents are the returning flow from waves breaking off
the beach..." vs HF's "Rube currents are the returning flow from waves
breaking off the beach...") before any Kotlin was written. This caught two
issues cheaply that would have been much harder to debug on-device:
sherpa-onnx's `tokens.txt` entries are base64-encoded byte sequences (not
raw text — needed byte-level concatenation before UTF-8 decoding, since some
tokens are partial multi-byte sequences), and greedy decoding needs a
repetition guard (it doesn't reliably hit the natural end-of-text token).

### Android app

New Gradle/Kotlin project at [`android/`](android/) — see
[`android/README.md`](android/README.md) for how to build and run it. No
existing Android tooling was set up in this repo; a full toolchain
(SDK check, Gradle invocation, physical device pairing) was stood up this
session. Bugs discovered building it, in case they recur:

- **`android.useAndroidX=true`** missing from `gradle.properties` — AGP
  refuses to build with AndroidX dependencies otherwise.
- **File-ownership scoped-storage trap**: `adb push` into the app's
  `Android/data/<pkg>/files/` writes files owned by the `shell` UID, which
  Android's storage FUSE layer then blocks the app's own process from
  opening (`OrtException: ... failed: system error number 13`), even though
  raw Unix permission bits look fine. Fixed by pushing to a neutral scratch
  path and using `run-as <pkg>` to copy into the app's true internal storage
  (`/data/data/<pkg>/files`, not subject to this restriction). Documented in
  `android/README.md` since it'll bite again on any fresh device.
- **OxygenOS install confirmation**: the first install of an unsigned debug
  APK shows an on-device "Install anyway?" prompt `adb install` cannot
  dismiss programmatically — needs a physical tap. Reinstalls skip it.
- **Work-profile ambiguity**: this test device has a Work profile (user 10)
  alongside the main profile (user 0); `adb shell am start` without
  `--user 0` either fails outright or targets the wrong profile silently.
- **Tensor axis-order bug** (real correctness bug, not environment): the
  IndicConformer encoder's output and the CTC decoder's input are
  channel-first (`[batch, 1024, time]`), not channel-last. Kotlin code
  initially assumed channel-last (matching a wrong mental model formed while
  writing it, not the ONNX metadata actually inspected earlier), causing an
  `ORT_INVALID_ARGUMENT` shape mismatch (`index 1: got 69, expected 1024`)
  on first real on-device run. Fixed and matches the Python reference
  pipeline's actual (untransposed) tensor flow.

### Validated on the physical device so far

Device: OnePlus CPH2707, Android 16, **arm64-v8a**, ~7.5GB RAM.

- Build → install → launch toolchain confirmed working end-to-end.
- All three ONNX sessions load successfully from internal storage.
- **LID inference on live microphone input confirmed correct**: speaking
  Hindi produced `LID: hindi (english=0.000)` with sensible score
  distribution across the 9 labels, in 442ms.
- IndicConformer's CTC path was hit (real bug found and fixed, see above);
  re-run pending after the fix.
- Whisper's on-device decode loop has not yet been exercised with live mic
  input (only validated in Python against sherpa-onnx's ONNX files
  offline, see above).
- Memory: not yet captured post-fix; the app reports `App PSS` / native
  heap / device-available RAM on every run screen specifically so this
  number is easy to pull each time.

### Known open items

- Re-verify IndicConformer end-to-end on-device after the axis-order fix,
  and exercise the Whisper (English) path on-device for the first time.
- Whisper's mel spectrogram is hand-rolled in Kotlin (DFT-via-precomputed-basis,
  same technique as the Python exports) since sherpa-onnx's prebuilt
  encoder/decoder came without a preprocessor of our own to export — it's
  the one feature-extraction path not validated by a direct diff against a
  reference module, only by matching HF Whisper's output in Python.
- Capture real memory numbers post-fix to actually answer "does it fit."
- APK is not currently shareable — models are pushed via `adb`/`run-as`
  outside the APK; someone else installing just the APK would get a load
  failure. See conversation for options (bundle as assets vs. download on
  first launch) if a shareable build is wanted later.
- This is still the fallback architecture per the original plan doc's own
  analysis (two resident encoders, LID on the critical path, no
  code-switching handling) — validates feasibility of shipping *something*
  on this hardware, not the target Phase 1/2 (LayRA continual-learning +
  distillation) architecture.
