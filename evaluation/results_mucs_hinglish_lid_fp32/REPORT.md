# Real Hinglish LID benchmark

MUCS Hindi-English code-switched test speech. VoxLingua has no Hinglish class, so this report characterizes routing behavior rather than claiming monolingual LID accuracy.

| Metric | Result |
|---|---:|
| Valid real utterances | 3135 |
| Audio hours | 5.65 |
| Routed to English | 0.10% |
| Routed to Indic | 99.90% |
| Dominant-language agreement (ties excluded) | 86.01% |
| English-ratio/English-score Spearman correlation | 0.351 |
| LID P50 | 121 ms |
| LID P95 | 241 ms |
| Peak process memory | 1993 MiB |

## Routing by annotated English-word ratio

| English ratio | N | English route | Mean English score | Mean Hindi score |
|---|---:|---:|---:|---:|
| 0-5% | 604 | 0.00% | 0.06% | 95.72% |
| 5-10% | 216 | 0.00% | 0.02% | 97.90% |
| 10-15% | 222 | 0.45% | 0.22% | 97.04% |
| 15-20% | 307 | 0.33% | 0.14% | 96.67% |
| 20-25% | 257 | 0.00% | 0.00% | 96.86% |
| 25-30% | 227 | 0.00% | 0.00% | 96.81% |
| 30-35% | 237 | 0.00% | 0.02% | 93.75% |
| 35-40% | 243 | 0.00% | 0.11% | 93.66% |
| 40-45% | 148 | 0.00% | 0.02% | 94.76% |
| 45-50% | 264 | 0.00% | 0.03% | 92.38% |
| 50-55% | 46 | 0.00% | 0.09% | 96.80% |
| 55-60% | 140 | 0.00% | 0.10% | 85.00% |
| 60-65% | 50 | 0.00% | 0.03% | 90.53% |
| 65-70% | 58 | 0.00% | 0.29% | 77.95% |
| 70-75% | 56 | 0.00% | 0.07% | 75.56% |
| 75-80% | 19 | 0.00% | 0.08% | 88.06% |
| 80-85% | 10 | 0.00% | 0.02% | 86.85% |
| 85-90% | 10 | 10.00% | 8.34% | 75.35% |
| 95-100% | 21 | 0.00% | 2.89% | 70.65% |

## Top-label counts

| Label | Count |
|---|---:|
| hindi | 3021 |
| telugu | 30 |
| gujarati | 25 |
| bengali | 23 |
| kannada | 12 |
| marathi | 11 |
| tamil | 6 |
| malayalam | 4 |
| english | 3 |

## Interpretation limit

The dominant-language agreement diagnostic compares English-majority clips with the English route and Hindi-majority clips with the Indic route. It is not ground-truth Hinglish accuracy; an utterance-level router cannot represent within-utterance code switching.
