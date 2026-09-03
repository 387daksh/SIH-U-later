# Real multilingual pipeline benchmark

FLEURS test speech; 100 deterministic recordings per language. Results are corpus-level unless stated otherwise.

## Overall

| Metric | Result |
|---|---:|
| Samples | 10 |
| Audio hours | 0.04 |
| Final language accuracy | 100.00% |
| Coarse English/Indic accuracy | 100.00% |
| Macro routed WER | 18.71% |
| Micro routed WER | 16.92% |
| Macro routed CER | 7.28% |
| Macro oracle WER | 18.71% |
| Macro oracle CER | 7.28% |
| Sentence error rate | 100.00% |

## Per-language results

| Language | LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| hindi | 100.00% | 6.25% | 4.17% | 6.25% | 4.17% | 776 | 776 |
| gujarati | 100.00% | 8.70% | 3.97% | 8.70% | 3.97% | 684 | 684 |
| marathi | 100.00% | 8.33% | 4.48% | 8.33% | 4.48% | 447 | 447 |
| kannada | 100.00% | 10.00% | 0.41% | 10.00% | 0.41% | 1146 | 1146 |
| malayalam | 100.00% | 15.00% | 1.30% | 15.00% | 1.30% | 579 | 579 |
| tamil | 100.00% | 6.25% | 1.47% | 6.25% | 1.47% | 599 | 599 |
| telugu | 100.00% | 7.14% | 0.89% | 7.14% | 0.89% | 404 | 404 |
| odia | 100.00% | 52.38% | 37.50% | 52.38% | 37.50% | 470 | 470 |
| bengali | 100.00% | 63.64% | 11.59% | 63.64% | 11.59% | 441 | 441 |
| english | 100.00% | 9.38% | 6.98% | 9.38% | 6.98% | 1396 | 1396 |

## Language classification

| Language | Precision | Recall | F1 | N |
|---|---:|---:|---:|---:|
| hindi | 100.00% | 100.00% | 100.00% | 1 |
| gujarati | 100.00% | 100.00% | 100.00% | 1 |
| marathi | 100.00% | 100.00% | 100.00% | 1 |
| kannada | 100.00% | 100.00% | 100.00% | 1 |
| malayalam | 100.00% | 100.00% | 100.00% | 1 |
| tamil | 100.00% | 100.00% | 100.00% | 1 |
| telugu | 100.00% | 100.00% | 100.00% | 1 |
| odia | 100.00% | 100.00% | 100.00% | 1 |
| bengali | 100.00% | 100.00% | 100.00% | 1 |
| english | 100.00% | 100.00% | 100.00% | 1 |

## Final-language confusion matrix

Rows are references; columns are predictions.

| ref \ pred | hindi | gujarati | marathi | kannada | malayalam | tamil | telugu | odia | bengali | english |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| gujarati | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| marathi | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| kannada | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| malayalam | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| tamil | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 |
| telugu | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 |
| odia | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| bengali | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| english | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Timing

| Stage | Mean ms | P50 ms | P95 ms |
|---|---:|---:|---:|
| Complete routed pipeline | 694 | 589 | 1283 |
| VoxLingua LID | 188 | 174 | 311 |
| Aggregate real-time factor | 0.054 |  |  |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
