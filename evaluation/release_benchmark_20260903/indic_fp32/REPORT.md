# Real multilingual pipeline benchmark

FLEURS test speech; 100 deterministic recordings per language. Results are corpus-level unless stated otherwise.

## Overall

| Metric | Result |
|---|---:|
| Samples | 900 |
| Audio hours | 3.15 |
| Final language accuracy | 99.56% |
| Final language accuracy 95% CI | 98.86%–99.83% |
| Legacy restricted-Vox accuracy | 87.67% |
| Coarse English/Indic accuracy | 100.00% |
| Macro language F1 | 99.56% |
| Macro routed WER | 20.06% |
| Micro routed WER | 19.77% |
| Macro routed CER | 7.12% |
| Micro routed CER | 7.22% |
| Macro oracle WER | 19.84% |
| Micro oracle WER | 19.54% |
| Macro oracle CER | 6.83% |
| Micro oracle CER | 6.96% |
| Sentence error rate | 89.11% |

## Per-language results

| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 98.00% | 100.00% | 13.53% | 6.10% | 13.53% | 6.10% | 1231 | 1997 |
| gujarati | 99.00% | 97.00% | 19.77% | 8.62% | 18.35% | 6.76% | 1180 | 1789 |
| marathi | 97.00% | 100.00% | 20.01% | 7.11% | 20.01% | 7.11% | 1462 | 2681 |
| kannada | 100.00% | 100.00% | 19.06% | 5.56% | 19.06% | 5.56% | 1476 | 2300 |
| malayalam | 99.00% | 100.00% | 19.99% | 5.65% | 19.99% | 5.65% | 1159 | 2115 |
| tamil | 97.00% | 100.00% | 28.29% | 12.02% | 28.29% | 12.02% | 913 | 1829 |
| telugu | 99.00% | 100.00% | 23.62% | 7.42% | 23.62% | 7.42% | 874 | 1351 |
| odia | 0.00% | 99.00% | 19.60% | 5.61% | 19.07% | 4.90% | 891 | 1513 |
| bengali | 100.00% | 100.00% | 16.68% | 5.98% | 16.68% | 5.98% | 979 | 1540 |

## Language classification

| Language | Precision | Recall | F1 | N |
|---|---:|---:|---:|---:|
| hindi | 96.15% | 100.00% | 98.04% | 100 |
| gujarati | 100.00% | 97.00% | 98.48% | 100 |
| marathi | 100.00% | 100.00% | 100.00% | 100 |
| kannada | 100.00% | 100.00% | 100.00% | 100 |
| malayalam | 100.00% | 100.00% | 100.00% | 100 |
| tamil | 100.00% | 100.00% | 100.00% | 100 |
| telugu | 100.00% | 100.00% | 100.00% | 100 |
| odia | 100.00% | 99.00% | 99.50% | 100 |
| bengali | 100.00% | 100.00% | 100.00% | 100 |

## Final-language confusion matrix

Rows are references; columns are predictions.

| ref \ pred | hindi | gujarati | marathi | kannada | malayalam | tamil | telugu | odia | bengali | english |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hindi | 100 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| gujarati | 3 | 97 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| marathi | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| kannada | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 | 0 |
| malayalam | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 | 0 |
| tamil | 0 | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 | 0 |
| telugu | 0 | 0 | 0 | 0 | 0 | 0 | 100 | 0 | 0 | 0 |
| odia | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 99 | 0 | 0 |
| bengali | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 100 | 0 |

## Timing

| Stage | Mean ms | P50 ms | P95 ms |
|---|---:|---:|---:|
| Complete routed pipeline | 1188 | 1074 | 2062 |
| VoxLingua LID | 219 | 203 | 369 |
| Routed ASR after LID | 968 | 873 | 1686 |
| Aggregate real-time factor | 0.094 |  |  |

## Error and runtime details

| Metric | Value |
|---|---:|
| Routed word substitutions | 2539 |
| Routed word deletions | 346 |
| Routed word insertions | 443 |
| Legacy LID expected calibration error | 6.45% |
| Peak process working set | 4377 MiB |

## Metric protocol

Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.

## Limits

FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.
