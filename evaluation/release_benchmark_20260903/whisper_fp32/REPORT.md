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
| Macro routed WER | 13.13% |
| Micro routed WER | 13.13% |
| Macro routed CER | 5.75% |
| Micro routed CER | 5.75% |
| Macro oracle WER | 13.13% |
| Micro oracle WER | 13.13% |
| Macro oracle CER | 5.75% |
| Micro oracle CER | 5.75% |
| Sentence error rate | 82.00% |

## Per-language results

| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| english | 100.00% | 100.00% | 13.13% | 5.75% | 13.13% | 5.75% | 1082 | 1741 |

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
| Complete routed pipeline | 1142 | 1082 | 1741 |
| VoxLingua LID | 203 | 197 | 345 |
| Routed ASR after LID | 939 | 883 | 1408 |
| Aggregate real-time factor | 0.114 |  |  |

## Error and runtime details

| Metric | Value |
|---|---:|
| Routed word substitutions | 210 |
| Routed word deletions | 33 |
| Routed word insertions | 52 |
| Legacy LID expected calibration error | 0.01% |
| Peak process working set | 2308 MiB |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
