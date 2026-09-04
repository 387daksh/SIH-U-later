# Real multilingual pipeline benchmark

FLEURS test speech; 100 deterministic recordings per language. Results are corpus-level unless stated otherwise.

## Overall

| Metric | Result |
|---|---:|
| Samples | 100 |
| Audio hours | 0.28 |
| Final language accuracy | 100.00% |
| Final language accuracy 95% CI | 96.30%–100.00% |
| Legacy restricted-Vox accuracy | 100.00% |
| Coarse English/Indic accuracy | 100.00% |
| Macro language F1 | 100.00% |
| Macro routed WER | 79.34% |
| Micro routed WER | 79.34% |
| Macro routed CER | 45.73% |
| Micro routed CER | 45.73% |
| Macro oracle WER | 79.34% |
| Micro oracle WER | 79.34% |
| Macro oracle CER | 45.73% |
| Micro oracle CER | 45.73% |
| Sentence error rate | 89.00% |

## Per-language results

| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| english | 100.00% | 100.00% | 79.34% | 45.73% | 79.34% | 45.73% | 914 | 1596 |

## Language classification

| Language | Precision | Recall | F1 | N |
|---|---:|---:|---:|---:|
| english | 100.00% | 100.00% | 100.00% | 100 |

## Final-language confusion matrix

Rows are references; columns are predictions.

| ref \ pred | hindi | gujarati | marathi | kannada | malayalam | tamil | telugu | odia | bengali | english |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| english | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 100 |

## Timing

| Stage | Mean ms | P50 ms | P95 ms |
|---|---:|---:|---:|
| Complete routed pipeline | 1214 | 914 | 1596 |
| VoxLingua LID | 200 | 191 | 335 |
| Routed ASR after LID | 1014 | 707 | 1365 |
| Aggregate real-time factor | 0.121 |  |  |

## Error and runtime details

| Metric | Value |
|---|---:|
| Routed word substitutions | 469 |
| Routed word deletions | 182 |
| Routed word insertions | 1131 |
| Legacy LID expected calibration error | 0.01% |
| Peak process working set | 2383 MiB |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
