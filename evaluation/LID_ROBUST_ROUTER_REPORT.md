# Robust Indian-English coarse router pilot

## Change

The nine-language IndicConformer voting stage is unchanged. Coarse routing can now use the frozen 256-dimensional VoxLingua ECAPA embedding extractor with a 5,589-byte logistic head. A candidate with ECAPA English probability at least 0.30 is verified using Whisper-tiny's English language-token probability; it routes to Whisper only when that score is also at least 0.30.

## Data integrity

- Training: 900 Indian-English Common Voice clips and 900 FLEURS Indic clips.
- Validation: 270 + 270 clips; 99.26% balanced accuracy (536/540).
- Extra pure Indian-English test: 247/250 correct (98.8%); this split contains only English, so balanced accuracy is not defined.
- 1,337 speakers total and zero speaker overlap across train, validation, and test.
- All speakers in the existing 1,000-clip FLEURS release benchmark were excluded before selecting FLEURS training/validation clips.
- The Indic pilot examples come from unused speakers in FLEURS's upstream test pool. This preserves the local benchmark's speaker holdout but is not an official publishable FLEURS test protocol.

Sources: `google/fleurs` pinned at `70bb2e84b976b7e960aa89f1c648e09c59f894dd` (CC-BY-4.0) and `ishands/commonvoice-indian_accent` (Common Voice derivative, CC0-1.0).

## Held-out results

| Test | Legacy Vox route | Binary head alone | Binary + Whisper verifier |
|---|---:|---:|---:|
| FLEURS, 100 clips x 10 languages | 100.0% | 99.8% | 100.0% |
| FLEURS Odia | 100.0% | 100.0% | 100.0% |
| MUCS Hindi-majority routed Indic | 99.92% | 62.97% | 97.55% |
| MUCS English-majority routed English | 0.24% | 67.56% | 22.44% |
| MUCS dominant-language agreement | 86.01% | 63.61% | 87.07% |
| MUCS 95-100% English bucket routed English | 0% | 80.95% | 57.14% |

MUCS contains real code-switched speech. Its English/Hindi word ratios are a routing diagnostic, not utterance-level ground-truth language labels. The binary head alone is therefore not the deployment setting; the verifier is required.

## Cost on this machine

| Measurement | Result |
|---|---:|
| Legacy LID CPU, same 100 FLEURS clips, P50 / P95 | 228.9 / 403.0 ms |
| Binary LID CPU, same clips, P50 / P95 | 229.6 / 431.6 ms |
| Whisper verifier earlier 4,135-clip CPU benchmark, P50 / P95 | 162.6 / 172.0 ms |
| Verifier invocation rate, FLEURS / MUCS | 10.2% / 49.1% |
| Real saved-mic English, complete CPU pipeline | 1.42 s |
| Held-out Hindi, complete CPU pipeline | 1.87 s |
| Loaded pipeline disk size including head | 2,610.76 MiB |
| Full CPU process peak after English + Hindi inference | 3,784 MiB |

The head changes disk size by only 0.005 MiB. It does not reduce the 2.38 GiB FP32 IndicConformer footprint; model compression remains a separate deployment decision.
