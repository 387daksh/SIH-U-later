# SIH speech pipeline release benchmark — 2026-09-03

## Verdict

- **Accuracy default:** FP32 IndicConformer + FP32 Whisper + FP32 VoxLingua.
- **Speed/size demo option:** hybrid INT8 IndicConformer, with a documented Odia regression. It is not the accuracy default.
- **Rejected:** aggressive INT8 IndicConformer, dynamic INT8 Whisper, dynamic INT8 VoxLingua, and the current FP16 Indic artifact.
- **Release blocker:** VoxLingua is not a robust English/Indic router for Indian-accented English or Hinglish.

The current Indic runtime is custom ONNX Runtime CTC inference. It is **not sherpa-onnx** and it does not load the unused RNNT graph.

## Test protocol

| Property | Value |
|---|---|
| Hardware | AMD64 Family 25 Model 116; 16 logical CPUs; 15.3 GiB RAM |
| Execution provider | ONNX Runtime 1.23.2 CPUExecutionProvider |
| ASR corpus | FLEURS test audio; 100 real utterances per language |
| Indic set | 900 utterances; 3.148 h; 9 languages |
| English set | 100 utterances; 0.279 h |
| Hinglish LID set | MUCS-Hinglish; 3,135 valid utterances; 5.651 h |
| Timing | Single process, wall clock, complete routing pipeline |
| RAM | Windows peak process working set; full pipeline resident |

Every variant used the same manifest and routing code. Results are stored per sample in append-only JSONL. Corpus WER/CER use total edit counts, not the mean of per-utterance percentages.

## Indic ASR: 900-utterance result

| Variant | Macro WER | Micro WER | Macro CER | Final language accuracy | P50 | P95 | RTF | Peak RAM | Indic artifact |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| FP32 | **20.06%** | **19.77%** | **7.12%** | **99.56%** | 1,074 ms | 2,062 ms | 0.094 | 4,377 MiB | 2,438 MiB repository / 2,381 MiB clean |
| Hybrid INT8 | 21.84% | 21.56% | 8.10% | 99.33% | **550 ms** | **934 ms** | **0.047** | **3,943 MiB** | 737 MiB repository / 645 MiB clean |
| Aggressive INT8 | 21.89% | 21.60% | 8.18% | 99.22% | 585 ms | 1,001 ms | 0.050 | 4,077 MiB | 646 MiB repository / 631 MiB clean |

### WER by language

| Language | FP32 | Hybrid INT8 | Aggressive INT8 |
|---|---:|---:|---:|
| Hindi | **13.53%** | 14.63% | 14.38% |
| Gujarati | **19.77%** | 21.20% | 21.20% |
| Marathi | **20.01%** | 21.16% | 20.95% |
| Kannada | **19.06%** | 20.36% | 19.86% |
| Malayalam | **19.99%** | 22.07% | 22.38% |
| Tamil | **28.29%** | 28.98% | 29.61% |
| Telugu | **23.62%** | 24.55% | 24.55% |
| Odia | **19.60%** | 26.40% | 26.71% |
| Bengali | **16.68%** | 17.23% | 17.40% |

Hybrid INT8 cuts median latency by 48.8% and clean total disk by 66.5%, but Odia WER increases by 6.80 percentage points. Aggressive INT8 is smaller but is slower, uses more peak RAM, and is slightly less accurate than hybrid.

## English ASR: 100-utterance result

| Whisper variant | WER | CER | SER | P50 | P95 | Mean | Peak full-pipeline RAM | Decision |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| FP32 | **13.13%** | **5.75%** | 82% | 1,082 ms | 1,741 ms | **1,142 ms** | **2,308 MiB** | Keep |
| PyTorch dynamic INT8 Linear | 79.34% | 45.73% | 89% | 914 ms | 1,596 ms | 1,214 ms | 2,383 MiB | Reject |

Dynamic INT8 caused looping generations, individual WER above 20x, and 10-second outliers. Its lower P50 does not compensate for the severe accuracy loss and worse mean latency/RAM.

## Hinglish and Indian-English routing

On 3,135 valid MUCS-Hinglish clips, FP32 VoxLingua routed only 3 clips (0.096%) to English. In the 95–100% English-ratio bucket it routed 0/21 to English. Most output labels were Hindi (3,021/3,135). Dynamic INT8 produced the same coarse decisions, with P50 increasing from 121.1 ms to 123.1 ms and measured peak process RAM increasing from 1,993 MiB to 2,169 MiB.

This dataset does not define one universally correct label for every mixed utterance, so “Hinglish accuracy” is not claimed. The extreme failure on English-dominant clips is nevertheless a valid routing failure. A trained Indian-English/Indic binary head or an evaluated secondary Whisper language detector is required before calling the router robust.

## Clean deployment sizes

The validated `deployment_models` tree removes unused RNNT graphs, post-nets, duplicate TensorFlow/Flax/PyTorch Whisper weights, examples, documentation, and cache metadata.

| Component | Files | Size |
|---|---:|---:|
| Common FP32 VoxLingua + FP32 Whisper | 14 | 229.52 MiB |
| FP32 Indic CTC | 373 | 2,381.23 MiB |
| Hybrid INT8 Indic CTC | 6 | 645.42 MiB |
| Aggressive INT8 Indic CTC | 8 | 630.73 MiB |

| Complete runtime choice | Total disk | Reduction from FP32 |
|---|---:|---:|
| FP32 | 2,610.76 MiB | — |
| Hybrid INT8 Indic + FP32 common | 874.94 MiB | 66.49% |
| Aggressive INT8 Indic + FP32 common | 860.26 MiB | 67.05% |

All three clean directories were loaded by the real pipeline and produced valid Hindi and English transcriptions.

## Parameter and quantization inventory

| Model | Measured parameters | FP32 weights | FP16 artifact/estimate | INT8 artifact | INT4 |
|---|---:|---:|---:|---:|---|
| VoxLingua ECAPA + original classifier | 21.061M | 80.56 MiB | 40.32 MiB | 80.57 MiB dynamic state | Not produced |
| IndicConformer | 628.333M | 2,438.12 MiB repository | 1,220.63 MiB; not runnable on ORT CPU | 645.65 MiB repository | Not produced |
| Whisper-tiny | 37.761M | 144.07 MiB | 72.05 MiB estimate | 115.90 MiB dynamic state | Not produced |

The LID and Whisper INT8 numbers are serialized PyTorch states, not Android-ready artifacts. Only the Indic INT8 ONNX variants are validated standalone runtime models. FP16 Indic fails under ONNX Runtime 1.23.2 CPU because of graph precision/type incompatibilities; no accuracy or latency claim is made for it. INT4 was not attempted because this toolchain has no validated drop-in INT4 path and accuracy risk is already visible at INT8.

## Implemented hardening

- Minimal Indic CTC loader: preprocessor, shared encoder, CTC decoder, vocabulary and language masks only.
- One encoder pass followed by nine cheap language-mask decodes; the winning transcription is returned directly.
- Input checks for missing, empty, non-finite, too-short and digitally silent audio.
- Reproducible manifest/config hashes, runtime versions, model sizes, per-sample JSONL and resumable execution.
- Real WER, CER, SER, language accuracy, gender slices, P50/P95/max latency, RTF and peak process memory.
- Validated clean runtime packaging through `scripts/build_runtime_models.py`.

## Honest limitations

- FLEURS is clean read speech, not noisy live-microphone or spontaneous code-switch speech.
- MUCS-Hinglish is code-switched lecture speech; its language ratios are useful diagnostics but not a universal routing ground truth.
- Desktop CPU timing does not establish Android timing. Peak FP32/hybrid RAM is too high for many phones while all three models remain resident.
- Mobile deployment should lazy-load the selected ASR backend or use separate processes, then benchmark actual target devices.
- Sherpa-onnx integration, noise/reverb stress tests, long-form audio, thermal throttling, and Android NNAPI/QNN performance remain untested.

## Evidence files

- `indic_fp32/summary.json`, `runtime.json`, `samples.jsonl`
- `indic_hybrid_int8/summary.json`, `runtime.json`, `samples.jsonl`
- `indic_aggressive_int8/summary.json`, `runtime.json`, `samples.jsonl`
- `whisper_fp32/summary.json`, `runtime.json`, `samples.jsonl`
- `whisper_dynamic_int8/summary.json`, `runtime.json`, `samples.jsonl`
- `../results_mucs_hinglish_lid_fp32/summary.json`
- `../results_mucs_hinglish_lid_dynamic_int8/summary.json`
- `../../deployment_models/bundle_manifest.json`
