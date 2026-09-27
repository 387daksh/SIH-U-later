# Model tier decision — measured, 2026-09-28

All four configurations measured on the pinned local FLEURS test copy, 30 clips per language, all 9
Indic languages, CPU at 4 threads, identical audio loading, identical CTC decoding, identical
normalization. Raw per-sample output in `evaluation/mono_vs_multi_partials/`, summary in
`wer_comparison_results.json`, harness in `scripts/compare_mono_vs_multi.py`.

## The four configurations

| configuration | mean WER | mean RTF | peak RAM | disk | covers |
| --- | --- | --- | --- | --- | --- |
| multilingual 628M FP32 *(shipping today)* | 18.96% | 0.0997 | 3100 MB | 2381 MB | 22 languages, shared |
| **multilingual 628M MatMul-INT8** | **18.79%** | 0.0228 | 1676 MB | **864 MB** | 22 languages, shared |
| monolingual 120M FP32 | 19.24% | 0.0510 | 1407 MB | 459.7 MB | one language |
| **monolingual 120M MatMul-INT8** | 19.63% | **0.0198** | **1102 MB** | **178 MB** | one language |

Quantisation recipe for both: `quantize_dynamic(weight_type=QInt8, per_channel=True,
op_types_to_quantize=["MatMul"], extra_options={"MatMulConstBOnly": True})`.

## Quantising the shared model is free

The headline: **MatMul-INT8 on our 628M model costs nothing in accuracy and saves almost everything
else.** 2381 MB → 864 MB (2.76× smaller), RTF 0.0997 → 0.0228 (4.4× faster), peak RAM 3100 → 1676 MB,
and mean WER actually improves slightly (18.96% → 18.79%). Per-language deltas run −0.83 to +0.31 and
**no language is significantly different at 95%** in a paired bootstrap. Differences that small on 30
clips are noise, which is the point: the quantisation is not costing us anything measurable.

This also produces our best Odia figure to date: **17.21%**.

Why it works, and why the usual warning does not apply: dynamic INT8 is often reported as ~3× *slower*
because `quantize_dynamic` rewrites convolutions to `ConvInteger`, which onnxruntime's CPU provider
handles poorly — and in our case has no kernel for at all. A Conformer encoder is MatMul-dominated
(289 MatMul against 77 Conv in our encoder), so restricting quantisation to MatMul moves the bulk of
the compute onto integer kernels and leaves the conv front-end in FP32. Result: 217 MatMulInteger,
**0 ConvInteger**, loads cleanly, runs faster.

It also beats weight-only INT8, which dequantises before compute: that shrinks the file but leaves
weights FP32-sized in RAM, so it does not help a memory-constrained device.

## Do not ship `deployment_models/indic/hybrid_int8`

This repo already contained two INT8 exports predating the work above — `hybrid_int8` (646.5 MB) and
`aggressive_int8` (631.8 MB). Both have the **identical op graph** to the new `matmul_int8` (217
MatMulInteger, 0 ConvInteger, 72 MatMul, 77 Conv) and both load, so they look like strictly better
versions of it: same scheme, 218 MB smaller. Measured, they are not.

| | mean WER | Odia WER | mean RTF | peak RAM | disk |
| --- | --- | --- | --- | --- | --- |
| `matmul_int8` (new, per-channel) | **18.79%** | **17.21%** | **0.0228** | **1676 MB** | 864.1 MB |
| `hybrid_int8` (pre-existing) | 20.53% | **25.34%** | 0.0565 | 2116 MB | **646.5 MB** |

`hybrid_int8` is **1.74 points worse on average and 8.13 points worse on Odia**, plus 2.5× slower and
440 MB heavier in RAM. The only axis it wins is disk. Trading 8 points of Odia — the language the
problem statement makes hardest and our strongest differentiator — for 218 MB is not a trade worth
making, so `matmul_int8` is the artifact to ship and host.

The difference is scale granularity: the new export uses `per_channel=True`, the old one did not. The
219 MB size gap is larger than per-channel scale vectors alone account for and is not fully
explained; the WER, RTF and RAM differences are measured either way.

`aggressive_int8` was not benchmarked. It has the same op graph and a near-identical size to
`hybrid_int8`, so it is likely in the same family, but that is an assumption, not a measurement.

## The tier rule

Both tiers are our own models, both MatMul-INT8. Selection is by **how many languages this unit needs
offline**, not by phone class:

| unit needs | ship | disk | why |
| --- | --- | --- | --- |
| 1 language | monolingual INT8 | **178 MB** | 4.9× smaller than the shared model |
| 2–4 languages | monolingual INT8 × N | 356–712 MB | still under the shared model |
| **5+ languages, or unknown** | **multilingual INT8** | **864 MB** | cheaper than 5 × 178, and more accurate |

Crossover is 864 / 178 = **4.9 languages**. Below it, per-language wins on disk; above it the shared
model wins on disk *and* on accuracy (18.79% vs 19.63%) *and* covers 22 languages rather than 9.

Language scope is the right axis because this is an **offline** relay: on-demand fetch at the moment
of need is exactly what a disaster rules out, so each unit's repertoire has to be decided at
provisioning. A unit handed out in Odisha needs Odia + Hindi = 356 MB. A general-issue unit takes the
864 MB shared model.

RAM is a hard gate on top: where the shared model will not fit, the monolingual tier is the only
option regardless of language count. Both RAM figures above are desktop upper bounds (see caveats).

## Against the competing entry

Their current build ships ARTPARK-IISc SraVaani-1.0 INT8 TDT — one shared model for 9 Indic
languages — plus IndicConformer INT8 for English.

| | their shared STT | our shared INT8 | our per-language INT8 |
| --- | --- | --- | --- |
| disk | **464.7 MB** (9 langs) | 864 MB (22 langs) | 178 MB (1 lang) |
| Odia WER | 22.31% | **17.21%** | 19.54% |
| measured peak RAM | 1206–1561 MB *on device* | 1676 MB *desktop* | 1102 MB *desktop* |

They are 1.86× smaller on the shared model. We are **5.1 points better on Odia**, cover 22 languages
instead of 9, and our per-language tier is 2.6× smaller than their shared model for a unit that needs
one or two languages.

**We do not need to adopt their model.** Switching to SraVaani would trade away 5 points of Odia —
the language the problem statement makes hardest and our strongest differentiator — to save 400 MB.
The technique worth taking from them was the quantisation insight, and that is now applied to our own
models with better results than either of us had.

## Caveats, stated plainly

- **30 clips per language** gives ±2–4 point confidence intervals. Adequate for a go/no-go; not
  enough to resolve a real 1–2 point regression. Gujarati (+2.02) and Odia (+1.93) are the largest
  monolingual-vs-shared gaps with CI lower bounds just below zero, so those two should be re-checked
  at 100+ clips before the tier is finalised.
- **Peak RAM is desktop RSS, not on-device.** It includes ~500 MB of PyTorch that an Android build
  never loads (`import torch` alone is +498 MB, measured). These are upper bounds. The competitor's
  RAM figures are real on-device PSS, so the RAM columns above are **not** directly comparable, and we
  cannot claim a RAM win until `scripts/measure_on_device.sh` runs on a real phone.
- **Monolingual numbers are a floor.** The only runnable export is CTC-only, leaving the hybrid
  checkpoint's stronger RNNT head unmeasured.
- FLEURS is read speech, so all of these are optimistic relative to field conditions. Kathbath or
  IndicVoices audio would be a harder and more representative benchmark; neither is in this project.

## What remains

1. Re-run at 100+ clips, especially Gujarati and Odia.
2. Measure on-device RAM and RTF, replacing the desktop upper bounds.
3. Wire tier selection into the Android model fetcher, keyed on provisioned language count with a RAM
   gate.
4. Stage the quantised shared model (`deployment_models/indic/matmul_int8`) into the Android assets
   pipeline.
