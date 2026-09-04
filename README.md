# SIH offline multilingual speech pipeline

CPU-first Python prototype for English/Hindi/Indic microphone transcription. A frozen VoxLingua ECAPA embedding model plus a small Indian-English-vs-Indic head performs coarse routing; Whisper-tiny verifies plausible English candidates and transcribes English, while the shared IndicConformer CTC output selects and transcribes one of nine Indic languages.

## Layout

```text
sih_speech/       reusable pipeline package
scripts/          live microphone, dataset, benchmark, and footprint commands
tests/            fast offline regression checks
tools/            ONNX repair and model inspection utilities
tools/legacy/     synthetic smoke tools (not evaluation evidence)
models/           downloaded LID and Whisper models
evaluation/       real manifests, audio subsets, and benchmark reports
artifacts/        local microphone and synthetic recordings
android/          native Kotlin/ONNX Runtime microphone demo
```

The original root commands remain as compatibility launchers. The downloaded Indic model directories and completed evaluation artifacts intentionally retain their existing paths so old reports remain reproducible.

## Setup

```powershell
py -3.10 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Install `requirements-training.txt` only when rebuilding the coarse LID head.

## Run

```powershell
# Live microphone test
.\.venv\Scripts\python.exe -m scripts.live_mic --lid-head artifacts/lid/indian_english_head.pt --whisper-lid-threshold 0.3

# LID-only microphone test for English/Hindi/Hinglish
.\.venv\Scripts\python.exe -m scripts.live_mic --lid-only

# Transcribe a WAV file
.\.venv\Scripts\python.exe infer_file.py ".\artifacts\recordings\last_mic.wav" --lid-head artifacts/lid/indian_english_head.pt --whisper-lid-threshold 0.3

# Rebuild the speaker-disjoint binary LID head (downloads a bounded real-audio subset)
.\.venv\Scripts\python.exe -m pip install -r requirements-training.txt
.\.venv\Scripts\python.exe -m scripts.prepare_lid_pilot
.\.venv\Scripts\python.exe -m scripts.train_lid_head
.\.venv\Scripts\python.exe -m scripts.evaluate_lid_head

# Rebuild/summarize the real FLEURS benchmark
.\.venv\Scripts\python.exe -m scripts.benchmark

# Fast offline checks
.\.venv\Scripts\python.exe -m tests.test_routing
.\.venv\Scripts\python.exe -m tests.test_metrics
```

Use `-h` on any command for its options. See `evaluation/LID_ROBUST_ROUTER_REPORT.md` for the new router's real-data results and limitations. Synthetic tools under `tools/legacy` are smoke tests only.

For the on-device build, model staging, and ADB install steps, see [`android/README.md`](android/README.md).
