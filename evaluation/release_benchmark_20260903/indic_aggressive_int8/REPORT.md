# Real multilingual pipeline benchmark

FLEURS test speech; 100 deterministic recordings per language. Results are corpus-level unless stated otherwise.

## Overall

| Metric | Result |
|---|---:|
| Samples | 900 |
| Audio hours | 3.15 |
| Final language accuracy | 99.22% |
| Final language accuracy 95% CI | 98.40%–99.62% |
| Legacy restricted-Vox accuracy | 87.67% |
| Coarse English/Indic accuracy | 100.00% |
| Macro language F1 | 99.23% |
| Macro routed WER | 21.89% |
| Micro routed WER | 21.60% |
| Macro routed CER | 8.18% |
| Micro routed CER | 8.28% |
| Macro oracle WER | 21.50% |
| Micro oracle WER | 21.17% |
| Macro oracle CER | 7.73% |
| Micro oracle CER | 7.86% |
| Sentence error rate | 91.89% |

## Per-language results

| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 98.00% | 100.00% | 14.38% | 6.48% | 14.38% | 6.48% | 530 | 903 |
| gujarati | 99.00% | 96.00% | 21.20% | 9.74% | 18.87% | 7.10% | 531 | 788 |
| marathi | 97.00% | 100.00% | 20.95% | 7.57% | 20.95% | 7.57% | 639 | 1122 |
| kannada | 100.00% | 100.00% | 19.86% | 5.93% | 19.86% | 5.93% | 603 | 953 |
| malayalam | 99.00% | 100.00% | 22.38% | 6.43% | 22.38% | 6.43% | 691 | 1204 |
| tamil | 97.00% | 100.00% | 29.61% | 12.58% | 29.61% | 12.58% | 569 | 1063 |
| telugu | 99.00% | 100.00% | 24.55% | 7.96% | 24.55% | 7.96% | 540 | 824 |
| odia | 0.00% | 97.00% | 26.71% | 10.99% | 25.50% | 9.54% | 563 | 918 |
| bengali | 100.00% | 100.00% | 17.40% | 5.95% | 17.40% | 5.95% | 610 | 973 |

## Language classification

| Language | Precision | Recall | F1 | N |
|---|---:|---:|---:|---:|
| hindi | 94.34% | 100.00% | 97.09% | 100 |
| gujarati | 100.00% | 96.00% | 97.96% | 100 |
| marathi | 99.01% | 100.00% | 99.50% | 100 |
| kannada | 100.00% | 100.00% | 100.00% | 100 |
| malayalam | 100.00% | 100.00% | 100.00% | 100 |
| tamil | 100.00% | 100.00% | 100.00% | 100 |
| telugu | 100.00% | 100.00% | 100.00% | 100 |
| odia | 100.00% | 97.00% | 98.48% | 100 |
| bengali | 100.00% | 100.00% | 100.00% | 100 |

## Final-language confusion matrix

Rows are references; columns are predictions.

| ref \ pred | hindi | gujarati | marathi | kannada | malayalam | tamil | telugu | odia | bengali | english |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 100 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| gujarati | 4 | 96 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| marathi | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| kannada | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 | 0 |
| malayalam | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 |
| tamil | 0 | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 |
| telugu | 0 | 0 | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 |
| odia | 2 | 0 | 1 | 0 | 0 | 0 | 0 | 97 | 0 | 0 |
| bengali | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 100 | 0 |

## Timing

| Stage | Mean ms | P50 ms | P95 ms |
|---|---:|---:|---:|
| Complete routed pipeline | 625 | 585 | 1001 |
| VoxLingua LID | 195 | 184 | 318 |
| Routed ASR after LID | 429 | 401 | 680 |
| Aggregate real-time factor | 0.050 |  |  |

## Error and runtime details

| Metric | Value |
|---|---:|
| Routed word substitutions | 2724 |
| Routed word deletions | 400 |
| Routed word insertions | 511 |
| Legacy LID expected calibration error | 6.45% |
| Peak process working set | 4077 MiB |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
