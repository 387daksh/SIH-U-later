# Real Hinglish LID benchmark

MUCS Hindi-English code-switched test speech. VoxLingua has no Hinglish class, so this report characterizes routing behavior rather than claiming monolingual LID accuracy.

| Metric | Result |
|---|---:|
| Valid real utterances | 3135 |
| Audio hours | 5.65 |
| Routed to English | 0.10% |
| Routed to Indic | 99.90% |
| Dominant-language agreement (ties excluded) | 86.01% |
| English-ratio/English-score Spearman correlation | 0.352 |
| LID P50 | 123 ms |
| LID P95 | 249 ms |
| Peak process memory | 2169 MiB |

## Routing by annotated English-word ratio

| English ratio | N | English route | Mean English score | Mean Hindi score |
|---|---:|---:|---:|---:|
| 0-5% | 604 | 0.00% | 0.06% | 95.85% |
| 5-10% | 216 | 0.00% | 0.02% | 98.01% |
| 10-15% | 222 | 0.45% | 0.22% | 97.13% |
| 15-20% | 307 | 0.33% | 0.15% | 96.85% |
| 20-25% | 257 | 0.00% | 0.00% | 97.03% |
| 25-30% | 227 | 0.00% | 0.00% | 97.00% |
| 30-35% | 237 | 0.00% | 0.02% | 94.13% |
| 35-40% | 243 | 0.00% | 0.08% | 93.94% |
| 40-45% | 148 | 0.00% | 0.02% | 95.10% |
| 45-50% | 264 | 0.00% | 0.03% | 92.80% |
| 50-55% | 46 | 0.00% | 0.08% | 96.92% |
| 55-60% | 140 | 0.00% | 0.10% | 85.66% |
| 60-65% | 50 | 0.00% | 0.03% | 91.00% |
| 65-70% | 58 | 0.00% | 0.27% | 78.70% |
| 70-75% | 56 | 0.00% | 0.07% | 77.22% |
| 75-80% | 19 | 0.00% | 0.08% | 88.97% |
| 80-85% | 10 | 0.00% | 0.02% | 88.80% |
| 85-90% | 10 | 10.00% | 8.32% | 76.60% |
| 95-100% | 21 | 0.00% | 2.80% | 71.82% |

## Top-label counts

| Label | Count |
|---|---:|
| hindi | 3029 |
| telugu | 29 |
| bengali | 21 |
| gujarati | 20 |
| kannada | 12 |
| marathi | 11 |
| tamil | 6 |
| malayalam | 4 |
| english | 3 |

## Interpretation limit

The dominant-language agreement diagnostic compares English-majority clips with the English route and Hindi-majority clips with the Indic route. It is not ground-truth Hinglish accuracy; an utterance-level router cannot represent within-utterance code switching.
