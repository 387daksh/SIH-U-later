# SIH offline multilingual speech pipeline

CPU-first Python prototype for English/Hindi/Indic microphone transcription. VoxLingua performs coarse English-vs-Indic routing, Whisper-tiny handles English, and the shared IndicConformer CTC output selects and transcribes one of nine Indic languages.

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
```

The original root commands remain as compatibility launchers. The downloaded Indic model directories and completed evaluation artifacts intentionally retain their existing paths so old reports remain reproducible.

## Setup

```powershell
py -3.10 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## Run

```powershell
# Live microphone test
.\.venv\Scripts\python.exe -m scripts.live_mic --indic-dir ".\indic-conformer-600m-int8-onnx-patched"

# LID-only microphone test for English/Hindi/Hinglish
.\.venv\Scripts\python.exe -m scripts.live_mic --lid-only

# Transcribe a WAV file
.\.venv\Scripts\python.exe -m sih_speech transcribe --audio ".\artifacts\recordings\last_mic.wav" --indic-dir ".\indic-conformer-600m-int8-onnx-patched"

# Rebuild/summarize the real FLEURS benchmark
.\.venv\Scripts\python.exe -m scripts.benchmark

# Fast offline checks
.\.venv\Scripts\python.exe -m tests.test_routing
.\.venv\Scripts\python.exe -m tests.test_metrics
```

Use `-h` on any command for its options. `evaluation/results_fleurs_test_100/REPORT.md` is the completed real-speech report; synthetic tools under `tools/legacy` are smoke tests only.
