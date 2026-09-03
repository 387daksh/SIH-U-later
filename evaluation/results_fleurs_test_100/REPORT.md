# Real multilingual pipeline benchmark

FLEURS test speech; 100 deterministic recordings per language. Results are corpus-level unless stated otherwise.

## Overall

| Metric | Result |
|---|---:|
| Samples | 1000 |
| Audio hours | 3.43 |
| Final language accuracy | 99.40% |
| Final language accuracy 95% CI | 98.70%–99.72% |
| Legacy restricted-Vox accuracy | 88.90% |
| Coarse English/Indic accuracy | 100.00% |
| Macro language F1 | 99.40% |
| Macro routed WER | 20.97% |
| Micro routed WER | 20.56% |
| Macro routed CER | 7.87% |
| Micro routed CER | 7.96% |
| Macro oracle WER | 20.69% |
| Micro oracle WER | 20.27% |
| Macro oracle CER | 7.55% |
| Micro oracle CER | 7.67% |
| Sentence error rate | 90.70% |

## Per-language results

| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 98.00% | 100.00% | 14.63% | 6.66% | 14.63% | 6.66% | 438 | 753 |
| gujarati | 99.00% | 96.00% | 21.20% | 9.63% | 19.11% | 7.20% | 435 | 672 |
| marathi | 97.00% | 100.00% | 21.16% | 7.66% | 21.16% | 7.66% | 594 | 1097 |
| kannada | 100.00% | 100.00% | 20.36% | 5.92% | 20.36% | 5.92% | 502 | 804 |
| malayalam | 99.00% | 100.00% | 22.07% | 6.43% | 22.07% | 6.43% | 565 | 942 |
| tamil | 97.00% | 100.00% | 28.98% | 12.14% | 28.98% | 12.14% | 466 | 855 |
| telugu | 99.00% | 100.00% | 24.55% | 7.78% | 24.55% | 7.78% | 436 | 677 |
| odia | 0.00% | 98.00% | 26.40% | 10.67% | 25.71% | 9.94% | 461 | 767 |
| bengali | 100.00% | 100.00% | 17.23% | 6.05% | 17.23% | 6.05% | 483 | 756 |
| english | 100.00% | 100.00% | 13.13% | 5.75% | 13.13% | 5.75% | 856 | 1428 |

## Language classification

| Language | Precision | Recall | F1 | N |
|---|---:|---:|---:|---:|
| hindi | 95.24% | 100.00% | 97.56% | 100 |
| gujarati | 100.00% | 96.00% | 97.96% | 100 |
| marathi | 99.01% | 100.00% | 99.50% | 100 |
| kannada | 100.00% | 100.00% | 100.00% | 100 |
| malayalam | 100.00% | 100.00% | 100.00% | 100 |
| tamil | 100.00% | 100.00% | 100.00% | 100 |
| telugu | 100.00% | 100.00% | 100.00% | 100 |
| odia | 100.00% | 98.00% | 98.99% | 100 |
| bengali | 100.00% | 100.00% | 100.00% | 100 |
| english | 100.00% | 100.00% | 100.00% | 100 |

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
| odia | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 98 | 0 | 0 |
| bengali | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 100 | 0 |
| english | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 100 |

## Timing

| Stage | Mean ms | P50 ms | P95 ms |
|---|---:|---:|---:|
| Complete routed pipeline | 560 | 503 | 979 |
| VoxLingua LID | 160 | 149 | 264 |
| Routed ASR after LID | 399 | 342 | 793 |
| Aggregate real-time factor | 0.045 |  |  |

## Error and runtime details

| Metric | Value |
|---|---:|
| Routed word substitutions | 2925 |
| Routed word deletions | 435 |
| Routed word insertions | 563 |
| Legacy LID expected calibration error | 5.81% |
| Peak process working set | 4202 MiB |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
