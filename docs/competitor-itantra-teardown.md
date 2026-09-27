# Competitor teardown — "iTantra" (Team Falcons), SIH/ISRO PS-26173

**Compiled:** 2026-09-27 · **Target:** <https://itantra.vercel.app> and its linked source repo
<https://github.com/Precise-Goals/iTantra-Communication-Ecosystem> (Kotlin, 352 files, created
2026-09-01, last push 2026-09-26, 2 stars, **no LICENSE file**).

## How this was gathered (so every claim here is checkable)

The landing page is a Vite SPA — the served HTML is 1,257 bytes and contains no content. Everything
on the site lives in one bundle, `/assets/index-CKTldBQJ.js` (519,776 bytes), from which the copy and
data arrays were extracted. The repo was read through the GitHub API and `raw.githubusercontent.com`.
Their own evaluation docs under `docs/` are unusually detailed and are the most valuable part — far
more so than the website.

> **Name collision, worth knowing:** this team's project is also called *iTantra* and also targets
> PS-26173. Their package is `com.itantra`, team "Falcons", and their Kotlin filenames
> (`WifiDirectManager.kt`, `BluetoothRFCOMMManager.kt`) differ from ours
> (`BluetoothPeerTransport.kt`, `SutraTransmissionService.kt`), so it is a genuinely separate
> codebase — but the shared name will cause confusion at judging. Worth deciding how we handle that.

> **Licence warning:** their repo has **no licence file**, which means default copyright — all rights
> reserved. We can read it, learn from it, and cite it. We **cannot** copy their code, docs, or site
> copy into our project. Everything recommended below is either a public model, a published
> technique, or an idea to reimplement ourselves.

---

## 1. The single most valuable find: SraVaani 1.0

Their `docs/evaluation/sravaani/` contains a full, honest evaluation of a model we are not using:

**`ARTPARK-IISc/SraVaani-1.0`** — MIT licence, 4,242 downloads, gated=auto (one click), from ARTPARK
at IISc. Key properties:

- **One shared multilingual model covering 65 languages**, including **Odia natively**.
- **TDT (Token-and-Duration Transducer)** decoder — its native, trained decode path. Also ships a
  secondary CTC head (which is worse: 22.44% vs 19.31% avg).
- `transcribe()` has **no language parameter** — it infers language itself. That is a materially
  different design from our mask-per-language approach and would collapse our LID dependency.
- Architecture specifics (from their design doc, read off the model card): **128 mel channels**
  (we use 80), encoder output `[1, 1024, T']`, vocab 5000 with `BLANK_ID=5000`, durations `[0,1,2,3,4]`,
  `pred_hidden=640`, `pred_rnn_layers=1`, `max_symbols=10`, SentencePiece tokenizer. Every other STFT
  parameter (`frame=400`, `hop=160`, `n_fft=512`, preemphasis, log-guard, per-feature norm) is
  **identical to the NeMo/IndicConformer pipeline we already have**.
- Non-gated ONNX mirrors exist if the gate is a problem: `killbanhar/sravaani-onnx`,
  `Trendova/sravaani-int8`, `iAkashPaul/sravaani-indic-asr`.

**Their measured result, FLEURS test, 100 clips/language, 1 CPU thread:**

| | 8 non-English Indic avg |
| --- | --- |
| IndicConformer (sherpa INT8) | 19.99% WER |
| **SraVaani 1.0 (FP32 TDT)** | **19.31% WER** |

Size, for full repertoire: SraVaani **903 MB FP32 / 464.7 MB INT8 as one shared file for all 9 Indic
languages**, versus ~188 MB × 9 ≈ 1.88 GB for per-language IndicConformer. Roughly **half**.

**Why this matters to us directly:** it is a third option in the tiering decision we just measured.
Our shared multilingual IndicConformer is 2,381 MB FP32 / 647 MB INT8. SraVaani INT8 is **464.7 MB
and covers Odia**, in one file, with no LID gate. That is smaller than our shared model *and*
smaller than nine of our monolingual models.

**Their verdict was "keep IndicConformer"** — but read the reasoning before accepting it. They set an
arbitrary "≥3 points better" adoption gate, then failed SraVaani on a **flat 9-language average that
included English**, where SraVaani is −9.43 points (22.16% vs 12.73%). Their own later revision says
averaging English in "buries SraVaani's gains elsewhere." Excluding English — which is correct for us
too, since we use Whisper-tiny for English regardless — **SraVaani wins**. Their rejection is an
artifact of their scoring rule, not of the model.

---

## 2. Head-to-head: their numbers vs ours

Their evaluation used **`google/fleurs` test split, first 100 clips per language sorted by filename**,
scored **NFC → lowercase → strip Unicode `P*` → collapse whitespace**, corpus WER via `jiwer`, 1 CPU
thread. That is very close to our own harness (`scripts/compare_mono_vs_multi.py`), which makes these
broadly comparable for the first time.

| lang | Their IndicConformer (INT8, 100 clips) | Their SraVaani (FP32, 100) | **Our multi 628M (FP32, 30)** | **Our mono 120M (FP32, 30)** |
| --- | --- | --- | --- | --- |
| hi | 11.46 | **9.38** | 11.04 | 11.17 |
| gu | 19.57 | 19.52 | **14.91** | 15.99 |
| mr | 19.80 | 19.16 | **18.44** | 18.77 |
| kn | 17.40 | 17.96 | **17.09** | 17.49 |
| ml | 23.36 | 19.00 | **17.71** | 17.92 |
| ta | **31.94** | 33.03 | 34.97 | 33.88 |
| te | 21.81 | **21.10** | 22.08 | 21.17 |
| bn | **14.57** | 15.32 | 16.80 | 18.05 |
| **or** | — *(no model)* | 21.69 | **17.60** | 18.76 |

**Comparability caveats — do not quote this table without them:** ours is 30 clips vs their 100 (our
CIs are ±2–4 points); ours is FP32 vs their IndicConformer INT8 (a real confound that flatters us);
our selection is the first 30 of a seeded 100-clip subset, not their first 100; and our normalization
additionally strips Unicode symbols (`S*`). Treat differences under ~3 points as inconclusive.

**Where we appear genuinely ahead:**

- **Odia: 17.60% for us vs 21.69% for their best-available Odia (SraVaani).** ~4 points, the largest
  gap in the table, on the language the PS makes hardest. Their shipped IndicConformer has **no Odia
  model at all**.
- **Gujarati (14.91 vs 19.57) and Malayalam (17.71 vs 23.36)** against their shipped engine, though
  INT8-vs-FP32 explains part of it.

**Where they are ahead:** Hindi (SraVaani 9.38 vs our 11.04) and Bengali (14.57 vs 16.80).

---

## 3. Techniques worth adopting, ranked

### P0 — do these

1. **Evaluate SraVaani 1.0 ourselves, excluding English, on ≥100 clips/language.** Their numbers say
   it beats IndicConformer on Indic languages, covers Odia in one file, needs no LID, and is half the
   size. Our own harness already does this; it is one new backend. This is the highest-leverage item
   on the list.

2. **Stop using `quantize_dynamic` for INT8 — use weight-only INT8 instead.** Their
   `docs/TTS_QUANT_PLAN.md` measured `quantize_dynamic` (all layers) on MMS Marathi TTS:
   114.0 MB → 38.0 MB, but RTF 0.71 → **2.15 — 3.0× slower**, because it emits `ConvInteger`.
   They also note **NNAPI is deprecated from Android 15**, so "INT8 unlocks the NPU" is false.
   Their surviving recipe: **weight-only INT8, keeping the small duration predictor in FP32** —
   measured **928 MB → 229 MB across all ten voices**.

   > This independently confirms what we hit today: the 131 MB monolingual INT8 export failed to load
   > with `NOT_IMPLEMENTED: ConvInteger`. My earlier advice — re-quantise with unsigned weights so it
   > loads — would have produced a **working but ~3× slower** model. Weight-only INT8 is the correct
   > fix, and this changes our INT8 plan.

3. **Adopt claim-provenance tagging in the pitch.** Their site labels every number with one of four
   tags, and it is the most persuasive thing on the page:
   `MEASURED` (“verified on hardware”), `CALCULATED` (“physics calculated”), `CITED`
   (“field cited / Android 16”), `PROJECTED` (“field projected”). Their framing line: *"A range
   number without its precondition is not a claim."* Every tier also carries an explicit
   `precondition`. We have genuinely measured numbers; tagging them this way makes our honesty
   legible instead of invisible, and makes untagged competitor claims look worse by contrast.

4. **Non-gated sherpa-onnx IndicConformer mirror:**
   `huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx` — per-language INT8 ONNX +
   `tokens.txt`, ~188 MB each, no licence gate. Useful as a fallback given the gating friction we hit
   today. Caveat: **no Odia**, and it contains Assamese (`as`) which must never be substituted for
   Odia — that was a real bug in their registry.

### P1 — strong candidates

5. **Range amplification without new hardware.** This is the most creative part of their work and is
   mostly physics we can reuse:
   - **BLE Coded PHY (`PHY_LE_CODED`)** via `AdvertisingSetParameters`, connectionless extended
     advertising (254 B payload): **+19 dB, ~10×**, 150–250 m per hop.
   - **Flood relay**: `ttl = 9`, `msg_id`, **256-entry LRU dedup**, **100–500 ms random jitter** to
     prevent broadcast-storm collapse. 10 nodes ≈ 2.5 km. Delivery modelled as `1 − 0.9¹⁰ = 65%`.
   - **Kite / balloon elevation** (₹200 kite, 20–50 m height): clears the **7.9 m Fresnel midpoint
     radius**, converting ground diffraction loss to line-of-sight. 111 dB FSPL clearance, 2–3 km
     single hop.
   - **Improvised foil parabolic dish**: 40 cm foil-lined umbrella, η ≈ 0.5, 22° beamwidth,
     **+17 dBi** → 5–8 km fixed aim.
   - **DTN store-carry-forward, Spray-and-Wait capped at 8 copies**: 10,000 text bundles ≈ 600 KB.
     Unbounded range via a walking carrier.
   These are cheap to describe and demo, and they attack the weakest part of any phone-mesh pitch.

6. **Codebook frame compression.** A **4,096-entry disaster-phrase corpus** reduces a 50–300 B
   Protobuf frame to **60 B**. For a bandwidth-starved mesh this is a large win and is independent of
   the ASR stack.

7. **Runtime model download instead of APK bundling**, with integrity checking:
   `ModelDownloadManager`, `ModelHashStore`, `HashUtils`, `ArchiveExtractor`, `ModelRegistry`,
   models fetched into `filesDir/models/`. Their own `KNOWN_ISSUES.md` documents the failure mode to
   avoid: a Malayalam voice declared `sizeBytes = 67_222_458` arrived **15,884,288 bytes (23.6%)**,
   truncated, with `sha256 = null`, and failed protobuf parsing on load. **Verify hashes per file,
   not just per archive, and never allow a null hash.** This is directly relevant to our own
   per-language on-demand fetch design.

8. **Golden/parity testing for the feature pipeline.** They keep `MelFeatureGoldenTest`,
   `SraVaaniMelGoldenTest`, `TdtDecoderParityTest`, `CtcDecoderUnitTest` plus checked-in reference
   fixtures (`parity_reference_tokens.txt`, `parity_trace_t.txt`). Hand-rolled Kotlin mel + decoder
   drifting from the Python reference is exactly the bug class that silently wrecks on-device WER.
   We should have the same tests.

9. **Silero VAD v4** (~2.2 MB) plus a documented **energy + embedding hybrid VAD** claiming 96%
   pause/sentence-detection precision. We already have `models/vad` and a Silero downloader — worth
   comparing against their stated figure.

10. **`espeak-ng-data` (~7 MB) shared across all TTS voices** for real phonemization, with
    sherpa-onnx/Piper. One shared dependency rather than per-voice text frontends.

### P2 — note, don't chase

11. **MMS TTS covers the languages Piper/Coqui/Mimic3 don't.** Their `MODELS_README.txt` declares
    Kannada, Tamil, Telugu, Marathi, Odia TTS unsupported — but `TTS_QUANT_PLAN.md` shows they later
    self-converted **MMS voices for exactly those five at 107.7 MB each**. So MMS is the answer for
    the hard TTS languages. Relevant to us since we have FastPitch tooling; MMS is a cheaper path to
    coverage.

12. **Android 16 Direct-to-Cell**: `PROPERTY_SATELLITE_DATA_OPTIMIZED` manifest property, for burst
    text telemetry over LEO. Tagged `CITED`, dependent on Starlink D2C reaching India ~2027. Cheap to
    declare, good for a roadmap slide, not a demo.

13. **Protobuf wire format** (`itantra.proto`) — `TransceiverMessage` with
    `MessageType{SPEECH, ALERT, ACK, PING}`, `text`, `src_lang`, `dst_lang`, `sender_id`,
    `timestamp`, `confidence`, `sequence`. ~50–300 B, "~3× more compact than JSON", TCP socket
    `:8765` with ACK RTT measurement. Two ideas worth stealing conceptually: a **distinct ALERT type
    that overrides audio focus and forces max volume**, and **`src_lang` ≠ `dst_lang`** so the
    receiver synthesises in its own language.

---

## 4. Their measured device numbers (useful as our benchmark targets)

From `docs/evaluation/sravaani/phone/tdt/README.md` — real `dumpsys meminfo` and logcat evidence,
not marketing:

| Metric | IndicConformer baseline | SraVaani TDT pair |
| --- | --- | --- |
| Median RTF (CPH2467, low-range) | 0.212 | 0.260 |
| Median speech-end → STT-complete | 556.2 ms | 682.6 ms |
| Cold model load | not isolated (warm) | 5,261 ms |
| **TOTAL PSS after test** | **~737 MB** | **~1,364 MB** |

On a POCO X6 5G: cold load **5,915 ms**, warm median RTF **0.244**, speech-end → STT-complete
**410.5–420.8 ms**, PSS ~1,206 MB, archive `sravaani_tdt.tar.bz2` 401.5 MB → 464.7 MB extracted.

**Two things to take from this.** First, **SraVaani's on-device RAM cost is severe**: +627 MB PSS,
*more* than its 464.7 MB on-disk size, against their own <700 MB target. Our measured RAM decomposition
(~560 MB of which was desktop PyTorch that won't ship) suggests we should measure SraVaani's real
on-device footprint before adopting it, not just its WER. Second, their RTF is measured at ~0.21–0.26
on a phone versus our 0.04–0.12 on desktop CPU at 4 threads — **our RTF numbers are not device
numbers**, and we should stop implying they are.

---

## 5. Their problems — avoid these, and know them

Their own `docs/IMPROVEMENT_PLAN.md` is a self-audit that lists what is broken. This is both a
warning list for us and, frankly, competitive intelligence.

- **Mel feature extraction is 43.6× slower than necessary** (their §2, flagged "critical").
- **The mel pipeline does not match the NeMo training config** (§3.1) — a silent WER tax.
- **VAD clips the first phoneme of every utterance** (§3.3).
- **Fixed 800 ms endpointing delay**; STT and TTS are both **non-streaming** (§4.1–4.3).
- **The compulsory model bundle is 2.18 GB, not the 169 MB the README claims** (§5.2).
- **RAM: unbounded caches, and "the wrong number is being reported"** (§5.3).
- **`dstLang` is ignored by the receiver, and there is no translation anywhere in the codebase**
  (§7.1–7.2) — despite the proto field and the site's cross-language framing.
- **The auto-detect language toggle does nothing** (§7.3); **transmitted `confidence` is meaningless**
  (§7.4); **documentation describes a VAD that is not running** (§7.5); **phone mode is unreachable**
  (§7.7); **concurrent messages produce overlapping audio** (§7.8).

### Website claims that their own repo contradicts

Useful to know, and a reminder not to make the same class of claim ourselves:

| Site claims | Their repo says |
| --- | --- |
| "Every model is quantized, **under 12 MB**", "<12 MB PER STT MODEL" | `MODELS_README.txt`: Hindi STT INT8 is **~188 MB**; full bundle **2.18 GB** |
| "**Ten languages.** One tap each." | **No Odia STT model exists** in their source mirror; TTS shipped for only hi/gu/ml/bn/en |
| "RAM usage at inference **< 50 MB**" (on 2 GB device) | Measured **737 MB–1,364 MB TOTAL PSS** on real phones |
| "STT WER **< 8.5%** avg across all 10 languages" | Their own FLEURS eval: **19.18%–20.33%** avg |
| "Best language (English) **5.4% WER**" | Their own measurement: English **12.73%** |
| Live widget shows "WER 0.11 / MEM 8.2% / 148 ms" | Static hardcoded strings in the bundle, not live |

Their **range** section, by contrast, is genuinely rigorous — tagged, preconditioned, and audited
against the codebase. The accuracy/efficiency section is not. If we are compared head-to-head on
substantiation, our measured numbers plus provenance tags should win; their mesh-range story is the
part that will beat us if we don't answer it.

---

## 6. Where we already win — defend these

1. **Odia actually works for us.** 17.60% WER measured. Their shipped engine has no Odia model, and
   their only Odia option (SraVaani, 21.69%) is ~4 points worse. Given the PS, this is our strongest
   single differentiator.
2. **One shared model covering 22 Indic languages** with a per-language vocabulary mask, already
   exported and running.
3. **We have real statistical rigour**: paired bootstrap CIs over utterances across all 9 languages.
   Nothing in their repo does significance testing.
4. **We have a real measured tiering answer**: monolingual 120M vs multilingual 628M, mean Δ +0.23
   points, worst case +1.24, no language significantly worse. That is a defensible engineering
   decision with numbers behind it.
5. **TTS breadth via FastPitch + word-level prosody tooling**, where their TTS coverage is patchy and
   their quantization path is a 3× slowdown.

---

## 7. Action checklist

- [ ] **Benchmark `ARTPARK-IISc/SraVaani-1.0`** in `scripts/compare_mono_vs_multi.py` as a third
      backend: 100 clips/language, all 9 Indic, report **excluding English**, plus Odia separately.
- [ ] **Re-do our INT8 plan as weight-only INT8**, not `quantize_dynamic`. Verify it both *loads* and
      is *not slower* — measure RTF, don't assume.
- [ ] **Measure on-device**, not desktop: RTF and PSS on a real low-end phone. Our current RTF
      figures are desktop-CPU and shouldn't be presented as device numbers.
- [ ] **Add provenance tags** (MEASURED / CALCULATED / CITED / PROJECTED) + preconditions to every
      number in the pitch deck.
- [ ] **Answer the mesh-range story**: BLE Coded PHY, flood relay with TTL + LRU dedup + jitter, and
      at least one cheap elevation/directional trick. This is where they currently out-engineer us.
- [ ] **Add golden/parity tests** for the mel front-end and decoder against a Python reference.
- [ ] **Per-file hash verification** with no null-hash path in our model fetcher.
- [ ] **Evaluate MMS TTS** for the languages FastPitch doesn't cover well.
- [ ] **Decide the name-collision question** before judging.
- [ ] Consider the **4,096-entry disaster codebook** for frame compression.

## 8. Code-level novelty (from reading the cloned source)

Cloned to `competitor-itantra/` (gitignored): 59 Kotlin files / 11,004 lines, 12 Python export
scripts / 1,430 lines. Everything below is a *technique* to reimplement, not code to copy — their
repo has no licence. Their `//` comments are unusually good: most of these are empirically-earned
findings with the failing symptom recorded, which is why they're worth harvesting.

### 8.1 Correction to §1 and §6 — they already ship SraVaani, Odia included

`STTModule.kt`'s header documents the current shipped configuration: **SraVaani INT8 encoder +
hand-rolled TDT decoder for all nine Indic languages `hi gu mr kn ml ta te bn or`**, with
IndicConformer INT8 CTC retained **only for English**. So the "keep IndicConformer" verdict in their
T76 doc was superseded — they built the hybrid, and **their Odia gap is closed**.

All nine Indic languages share **one** cache entry (`SRAVAANI_CACHE_KEY = "sravaani"`), not one pair
per language — the opposite of their old per-language IndicConformer design, and the same shape as
our shared-model approach.

**Our Odia advantage narrows but holds:** ours 17.60% vs their shipped 22.31%. What changes is that
"they have no Odia" is no longer true and must not be said in the pitch.

### 8.2 Weight-only INT8 — and the RAM trap in it

`model-export/weight_only_int8.py` (88 lines) stores each Conv/ConvTranspose weight as per-output-channel
symmetric int8 behind a `DequantizeLinear` node, leaving activations FP32 so **no `ConvInteger` ever
enters the graph**. Only initializers used *exclusively* as input index 1 of Conv/ConvTranspose are
converted, gated on `min_elems=4096` and `ndim>=2`, with a `keep_fp32_prefixes` escape (they keep
VITS's 2.2 MB duration predictor in FP32). `DequantizeLinear` nodes are prepended to preserve
topological order.

**The caveat, in their own words:** *"The trade-off is RAM: weights are float again once loaded, so
resident memory is FP32-sized."*

> **This corrects my own earlier projection.** I estimated a low-end tier at ~400 MB peak by assuming
> INT8 weights shrink RAM. With weight-only INT8 they do **not** — the file is ~4× smaller, RAM is
> unchanged. The real three-way trade-off:
>
> | scheme | file | RAM | speed |
> | --- | --- | --- | --- |
> | FP32 | large | large | fast |
> | weight-only INT8 | **~4× smaller** | **unchanged (FP32-sized)** | fast |
> | `quantize_dynamic` (ConvInteger/MatMulInteger) | ~4× smaller | smaller | **~3× slower on Conv graphs** |
>
> Since RAM is the actual gate for our low-end tier, weight-only INT8 solves the *download* problem
> and not the *RAM* problem. We need to state which one we're claiming.

**A recipe they did not try, which likely fits our Conformer:** their 3× slowdown was measured on
Conv/ConvTranspose-heavy VITS. A Conformer is MatMul-dominated — the INT8 export we tested today had
**154 `MatMulInteger` nodes versus 54 `ConvInteger`**, and only the ConvInteger ones lacked a kernel.
So `quantize_dynamic` restricted to MatMul (`op_types_to_quantize=["MatMul"]`, leaving the conv
subsampling front-end FP32) should load, shrink both file *and* RAM, and avoid the slow path. That is
worth measuring — it may be the best of the three for us. Note their own ASR script
(`quantize_models.py`) still uses blanket `quantize_dynamic(weight_type=QuantType.QInt8)`.

### 8.3 Silero VAD — four gotchas they paid for and documented

`VADModule.kt` is the highest-value single file for us, since we already ship Silero:

- **The v4 combined-state export accepts only 512-sample windows.** They tested 512/1024/1536/1600;
  everything but 512 fails identically inside the graph's LSTM (`"Input X must have 3 dimensions
  only"`). The widely-cited 512/1024/1536 range applies to the *older split-h/c* export, not this
  one. They re-buffer their 1600-sample (100 ms) pipeline chunks into 512 internally rather than
  changing the pipeline cadence.
- **64 samples of the previous window must be prepended to every call** (`SILERO_CONTEXT_SIZE = 64`).
  The official v5 wrapper does this; *"without it the model's output is unreliable."*
- **State is one combined tensor of shape `[2, -1, 128]`** — hidden size 128, not the 64 an older
  split-h/c export uses.
- **Hysteresis**: release threshold `0.35` against an attack threshold of `0.5` — Silero's own
  recommended `threshold − 0.15`, which *"stops the detector chattering inside a word."*

They also expose an explicit `VadBackend { NEURAL, BASIC_ENERGY, UNAVAILABLE }` enum so the UI can
never assume the neural model is live, falling back to RMS energy when the model isn't downloaded.
Worth copying as a pattern: it makes a silent degradation visible.

### 8.4 Pre-roll lookback — the fix for clipped word onsets

`AudioCaptureModule.kt` keeps a rolling **3-chunk / 300 ms lookback** of audio heard while no segment
is open, and prepends it when VAD fires. Their reasoning: *"The VAD fires once the phrase is already
under way, so unvoiced onsets (/k/ /t/ /p/, initial fricatives) sit in the chunks just before the
trigger and were lost."* The pre-roll is deliberately **not** counted toward speech-confidence
accounting — it is context, not detected speech.

This is a direct WER win for free, and it is the concrete fix for the "VAD clips the first phoneme"
defect their own improvement plan lists. We should check whether our capture path has the same bug.

### 8.5 Adaptive endpointing — latency without truncation

Instead of one silence timeout they use three: **800 ms** default, dropping to **500 ms** once
≥800 ms of confident speech has accumulated, and **400 ms** while push-to-talk is held. Rationale:
the flat 800 ms wait was a hard floor under their "words said → STT complete" metric for *every*
utterance, while short fragments still need the long window so a hesitant speaker isn't cut off.
Cheap, and it attacks a latency floor rather than the model.

### 8.6 Echo gate — stops the relay transcribing itself

While this phone is playing a received message, mic input is discarded *and any half-built segment is
thrown away*, because *"whatever the microphone hears is our own loudspeaker."* For a phone-to-phone
relay this prevents a feedback loop where a played message is re-transcribed and re-broadcast. We
have the same relay topology and should confirm we handle it.

### 8.7 Mel front-end — where their 43.6× slowdown went, and the correctness fixes

Their own audit flagged mel extraction as 43.6× slower than needed; the code shows the fix and the
accuracy traps:

- The original implementation recomputed a **naive O(N²) DFT and rebuilt the entire mel filterbank on
  every frame** — *"~950 ms per 3 s of audio, RTF above 1.0 before the ONNX session even ran."*
  Replaced with a precomputed **bit-reversal radix-2 FFT**, a **sparse filterbank** (per band: first
  FFT bin + triangular weights), and **scratch buffers reused across frames** for constant
  allocation per utterance.
- **Slaney area normalization** (librosa's `norm="slaney"`): each triangle scaled by `2/(upper−lower)`
  so filters have equal *area*, not equal peak height. They record this as *"previously wrong (T23)"*
  — i.e. a silent accuracy bug that survived a while.
- **NeMo pads the 400-sample window to 512** rather than transforming 400 points directly, and uses a
  **symmetric** Hann window. Getting either wrong is a train/inference mismatch.
- Filterbanks are **cached per distinct mel count** (80 for IndicConformer, 128 for SraVaani) so two
  model families coexist without duplicating the pipeline.

### 8.8 TDT decoding — the actual mechanism

`TdtDecoder.kt` (116 lines, pure Kotlin, ONNX-free for testability). Per encoder frame it runs
`decoder_joint` up to `MAX_SYMBOLS = 10` times; the joint emits **5,006 logits = 5,001 token +
5 duration**. On a blank it advances `t += max(1, DURATIONS[argmax])` with
`DURATIONS = [0,1,2,3,4]` — **so the decoder skips frames instead of stepping one at a time**, which
is the whole point: fewer joint invocations per utterance than RNNT. A `!advanced` safety valve
increments `t` if `MAX_SYMBOLS` is exhausted without a blank, preventing an infinite loop. LSTM
`(h, c)` state is `640`-wide, zero-initialised **per utterance and never carried across utterances**.

Notably they **hand-rolled it because sherpa-onnx has zero TDT support** (verified by grepping the
package for `"tdt"` — no matches). If we adopt SraVaani, we inherit this same build-it-ourselves
requirement; the algorithm above is small enough that this is a day of work, not a project.

### 8.9 Two decode conventions that silently corrupt output

- **CTC blank is the LAST vocab entry, not id 0**, for NeMo/IndicConformer — confirmed on-device from
  Hindi's `tokens.txt`: `<unk> 0 … <blk> 5632`. Assuming blank=0 produces plausible-looking garbage.
- **SentencePiece id 0 (`<unk>`) renders as `" ⁇ "`, not its literal piece text.** They verified all
  5,000 pieces against `sp.decode()` in Colab and this was *the only mismatch* — which is also the
  evidence that the simpler `▁`→space join rule is safe for the other 4,999, letting them skip a
  native SentencePiece dependency entirely. That verification method is worth copying.

### 8.10 A confidence score that means something

`estimateConfidence()` is defined as the **softmax probability of the chosen token at each step that
emits a non-blank token**, and critically the softmax is taken **over the token slice only
(`0..VOCAB_SIZE`) — the duration logits are a separate distribution and must not be included**.
Their improvement plan separately admits the *transmitted* confidence is meaningless, so this is a
case where the local computation is sound but the wire value isn't wired up. We transmit routing
confidence too; worth checking ours is computed over the right slice.

### 8.11 Danda-aware sentence termination

`TextPostProcessor.kt` (28 lines) terminates sentences with the danda `।` for **hi, bn, or** and a
full stop for everything else, explicitly noting that **Marathi and Gujarati use a full stop in
modern writing** and are deliberately excluded. Capitalisation applies only to English, since Indic
scripts have no case. They chose rules over a punctuation-restoration model because it *"would cost
more RAM and latency than the whole rest of the pipeline."* That mr/gu exclusion is a genuine
orthographic detail most teams get wrong, and it affects WER when references carry the danda.

### 8.12 The ALERT audio path

Normal playback uses `STREAM_MUSIC`; an ALERT uses **`STREAM_ALARM`, which forces max volume and
bypasses Do Not Disturb**. Sequence: request `AUDIOFOCUS_GAIN` → save current alarm volume → set to
max → play → **restore the original volume**. They also record a real bug: `USAGE_VOICE_COMMUNICATION`
routes to the earpiece at reduced volume (*"the volume is too low"* on-device), so they use
`USAGE_MEDIA` to hit the speaker. Playback is serialised through a single queue because per-message
`AudioTrack`s produced overlapping audio.

### 8.13 Download robustness — four failure modes worth pre-empting

Directly relevant to our on-demand per-language fetch:

- **OkHttp caps concurrent requests per host at 5.** With ~17 packs fired in parallel and most models
  on huggingface.co, *"the 6th+ request just sits queued with zero visible progress"* — confirmed
  on-device as packs stuck at 0% forever. Raise the dispatcher's per-host cap above the pack count.
- **`.part` file races**: two near-simultaneous `download()` calls for the same pack raced two writers
  on one `.part`. Fixed by setting `Queued` **synchronously on the caller's thread** so check-then-set
  is atomic, not inside the launched coroutine.
- **Resume at piece granularity**: a pack whose small aux file failed after a successful large main
  file must not re-fetch the main file on retry.
- **Partial-extraction detection**: a TTS bundle without its `.onnx` is treated as corrupt, since a
  race between duplicate downloads could clobber another extraction's output.

Combined with the `KNOWN_ISSUES.md` truncated-file bug (declared 67,222,458 bytes, arrived
15,884,288, `sha256 = null`), the rule is: **per-file hash verification, no null-hash path, and
validate the extracted tree — not just the archive.**

### 8.14 Concurrency lessons recorded with symptoms

`AudioCaptureModule` notes that PTT release calls `flushAndTranscribe()` while the capture coroutine
is still appending, throwing `ConcurrentModificationException` on a real device — hence every buffer
access goes through `bufferLock`. `SocketTransport` caps outstanding unACKed pings at 50 *"so a peer
that stops replying can't leak entries"*, uses a 4-byte length prefix with a 64 KB message cap, a
5 s ping interval, and measures RTT by stamping send time per ping sequence and matching the ACK.

### 8.15 What they do *not* have

- **No device-capability tiering.** `DeviceProfile`/`DeviceProfileRepository` are identity only
  (UUID + build-fingerprint hash → a readable alias like `ITantra-A3F7`). There is no RAM or SoC
  probe and no model-selection-by-device-class anywhere in the codebase. **Our tiering concept is
  genuinely differentiated** — though our own measurement says the right axis is language scope with
  RAM as a hard gate, not phone tier.
- **No translation**, despite `src_lang`/`dst_lang` in the proto — confirmed both in their audit and
  by the absence of any translation component in the tree.
- **No streaming STT or TTS**, and no LID: language is a user selection (`SRAVAANI_LANGUAGES` is *"a
  plain lookup, never language detection"*). Our LID/routing work has no counterpart here.

### Addendum to the action checklist

- [ ] **Try `quantize_dynamic` limited to MatMul** on our Conformer (§8.2) and measure file, RAM and
      RTF together — this may beat both FP32 and weight-only INT8 for the low-end tier.
- [ ] **Re-state our INT8 claim precisely**: say whether we are reducing download or RAM, since
      weight-only INT8 does not reduce RAM.
- [ ] **Audit our Silero usage** against §8.3 — window size, 64-sample context, hysteresis, state shape.
- [ ] **Add a 300 ms pre-roll lookback** to capture (§8.4) and re-measure WER.
- [ ] **Add adaptive endpointing** (§8.5) — likely our cheapest latency win.
- [ ] **Add an echo gate** (§8.6) if our relay doesn't have one.
- [ ] **Verify our mel front-end** for Slaney area normalization, 400→512 padding, symmetric Hann (§8.7).
- [ ] **Check our CTC blank index convention** is last-entry, not 0 (§8.9).
- [ ] **Raise OkHttp's per-host cap** and add per-file hash verification before shipping on-demand fetch (§8.13).
- [ ] **Stop saying "they have no Odia"** (§8.1).

## 9. Second pass — full read of the source tree

§8 was written from a partial read (roughly a third of the tree: headers and greps). This section is
the complete pass — `ITantraForegroundService.kt`, `Telemetry.kt`, `ProtobufSerializer.kt`,
`WifiDirectManager.kt`, `BluetoothRFCOMMManager.kt`, `ModelDownloadManager.kt`, `ModelRegistry.kt`,
`CtcDecoder.kt`, the 8 test files, the remaining Python, and the three large docs I had downloaded
but not read (`README.md` 39 KB, `PRD.md`, `docs/latency-evidence/README.md` 30 KB). It contains more
than §8 did.

### 9.1 Corrections to §1, §5 and §8

- **They now ship TTS for all ten languages, not five.** `README.md`'s model catalog lists
  `vits-mms-{marathi,kannada,tamil,telugu,odia}` at 26.56 MB each, self-hosted. The
  "UNSUPPORTED — no free offline TTS source found" note in `MODELS_README.txt` is stale.
- **But those five MMS voices are `CC-BY-NC 4.0` — non-commercial.** Their own catalog says so. For
  a government/disaster deployment that is a real licensing defect in half their TTS coverage, and a
  trap for us if we take the same shortcut (§P2 item 11 in this doc suggested MMS — it needs this
  caveat). Their other licences are clean: Silero MIT, IndicConformer Apache-2.0, SraVaani MIT,
  Piper MIT, Mimic3 Apache-2.0, Coqui MPL-2.0, espeak-ng-data GPL-3.0/Apache-2.0.
- **Their Silero file is a v5+ export pinned to release `v6.2.3`**, despite being stored as
  `silero_vad_v4.onnx` "for historical naming reasons", SHA-256 verified rather than
  trust-on-first-download. Refining §8.3: the missing-context symptom was output collapsing to
  **near-zero for every input including real speech** (not merely "unreliable"), so the threshold
  was never crossed and the app **permanently fell back to energy-only detection**. They also keep
  **the loudest of the ~3 32 ms sub-windows per 100 ms chunk**, and added
  `reinitializeIfNeeded()` because on a fresh install the service start races the VAD download —
  previously only a force-stop fixed it.

### 9.2 Their models are all publicly downloadable — `Chgauravpc/itantra`

**`huggingface.co/Chgauravpc/itantra` is public and ungated:**

| file | size |
| --- | --- |
| `sravaani_tdt.tar.bz2` | 383.0 MiB — their **INT8 TDT encoder + decoder_joint + tokens** |
| `vits-mms-{kan,mar,ory,tam,tel}.tar.bz2` | ~102.8 MiB each — MMS TTS incl. **Odia (`ory`)** |

So the SraVaani INT8 ONNX export we would otherwise have to produce ourselves is a direct download,
and an Odia TTS voice exists. (Licence caution above applies to the MMS voices.)

### 9.3 `Telemetry.kt` — rubric-driven evidence generation (the biggest thing I missed)

They built a telemetry object explicitly against **PS-26173's four Latency criteria**, named in the
file: (1) words said → STT complete, (2) text received → audio played, (3) RTF, (4) sentence on
phone A → same sentence starts playing on phone B.

Mechanisms worth copying wholesale:

- **NTP-style cross-device clock sync**, so metric 4 is measurable at all:
  `offset = t1 − (t0 + t2)/2`, `rtt = t2 − t0`, from one ping/ACK round trip (t0/t2 our clock, t1 the
  peer's, carried in the ACK). Pulled into `Telemetry.computeOffsetAndRtt` so the formula has one
  unit-tested definition (`ClockOffsetUnitTest.kt`).
- **`nsToEpochMs()`** converts a monotonic `nanoTime` reading to wall-clock epoch by pairing a fresh
  `nanoTime`/`currentTimeMillis` sample and projecting backwards — needed because you must measure
  with a monotonic clock but join across devices on a wall clock.
- **A `(senderId, sequence)` join key** so the sender's CSV row and the receiver's CSV row for the
  same utterance can be paired. They note the sequence alone is insufficient.
- **RTF deliberately excludes queue wait and model load** — *"queue wait and model load are not the
  model's speed (T71)"* — with `waitMs` broken out as its own column.
- **Per-utterance CSV** appended to `filesDir/telemetry.csv`, 18 columns, plus a rolling median of
  the last 20 RTF values for the UI.

**Strategic read:** this lets them hand judges per-utterance evidence for exactly the four numbers
the rubric asks for. We have better WER rigour; they have better *latency* evidence infrastructure.
This is the single most valuable thing in their repo for us to match.

### 9.4 The mel front-end, in full — five NeMo details, each a silent WER tax

`STTModule.extractLogMelSpectrogram` documents the exact NeMo contract. Every one of these is worth
checking against our own front-end:

1. **`preemph = 0.97` applied once to the whole signal before framing**, not per-frame.
2. **`log_zero_guard_value = 2⁻²⁴ = 5.9604645e-8`**, explicitly *"not the 1e-10 used previously."*
3. **`torch.stft(center=True, pad_mode="constant")`**: frame `t` is centred at sample `t*HOP`, samples
   outside the audio are zero, and the physical frame count is `1 + size/HOP`.
4. **`normalize: per_feature` (per-mel-channel mean/std over the utterance) is mandatory.** Without
   it *"the encoder saw out-of-distribution magnitudes and collapsed to the same predicted token
   regardless of audio content (confirmed on-device: three different-length recordings all decoded
   to the same single repeated character)."* That is a spectacular, easily-misdiagnosed failure mode.
5. **NeMo's `get_seq_len()` reports one fewer valid frame than the physical count**
   (`floor(size/hop)` vs `1 + floor(size/hop)`) — an artifact of its batch-padding math. It
   normalises over only the valid frames, then **masks the last physical frame to exactly zero**, and
   uses **unbiased (N−1) variance** per `normalize_batch`. They reproduce this quirk deliberately
   because the golden reference comes from that code path.

Session config for reference: **2 intra-op threads, `ALL_OPT`, NNAPI attempted with CPU/XNNPACK
fallback**, ~197 MB native allocation per session, 2-entry LRU.

### 9.5 Golden-fixture and step-trace parity testing — the technique to steal

Two test patterns that are better than anything in our suite:

- **`MelFeatureGoldenTest`** asserts the Kotlin mel output matches NeMo's own
  `AudioToMelSpectrogramPreprocessor` **to ~1e-3**, against `fixture.wav` + `golden_features.npy`
  generated from a real FLEURS `hi_in` clip through the actual AI4Bharat checkpoint in Colab, with
  the generating steps committed (`docs/evaluation/nemo_preprocessor_hi.txt`).
- **`TdtDecoderParityTest` replays a recorded step-by-step trace** of the Python reference decoder,
  not just its final output. The stub asserts at **every step** that the Kotlin decoder requests the
  same encoder time index and feeds back the same last-emitted token — explicitly *"catching an
  off-by-one in the duration/frame-skip logic or the max_symbols guard even if it happened to still
  produce the right final transcript by coincidence."* Committed fixtures include per-step logits and
  per-step LSTM `h`/`c` in **and** out.

We port decoders and feature pipelines too. Recording a per-step trace from the Python reference and
replaying it as a fixture is strictly stronger than comparing final transcripts, and it is cheap.

### 9.6 Findings that bear directly on our current Wi-Fi Direct / Bluetooth work

Our last two commits are "Fix Wi-Fi Direct connect() race and make Bluetooth a real fallback" and
"Make Wi-Fi Direct actually retry and reconnect after a BUSY failure". They hit both and recorded
root causes:

- **The BUSY root cause is two owners of discovery.** `WifiDirectManager` *deliberately does not*
  call `startDiscovery()`, because `MeshHardwareManager` already runs its own `discoverPeers()` loop
  for the UI radar, and *"two independent channels both issuing discovery collide (confirmed
  on-device: 'Discovery failed: reason=2'/BUSY)"*. Their fix is single-source-of-truth discovery
  ownership: the second channel exists only to `connect()` and to observe connection-state changes.
  If our BUSY has the same shape, this is the fix rather than more retrying.
- **Retry policy**: `500 ms × 2^attempt` capped at 30 s, `MAX_RECONNECT_ATTEMPTS = 3`, then automatic
  Bluetooth fallback. `DISCOVERY_INTERVAL_MS = 15 s`.
- **The Bluetooth fallback deadlock**: if both devices only ever call `connectToDevice()` and neither
  calls `startServer()`, they can never connect — *"confirmed on-device: both sides logged 'BT
  connect error', neither was listening."* Previously server mode only started automatically after 3
  Wi-Fi failures, with no way to become listenable explicitly. **"Make Bluetooth a real fallback"
  requires someone to be listening**, which is exactly this. They also had to add
  `stopBluetoothServer()` because the toggle previously stuck "on" forever.
- **Dual-transport duplicate suppression**: they send on *every* transport that currently has a peer,
  and dedupe received messages with a **bounded 256-entry LRU** whose key **includes the timestamp,
  because sequence numbers restart at 1 when the sender's app restarts**. A peer reachable over both
  Wi-Fi Direct and Bluetooth is otherwise heard twice.
- Fixed RFCOMM service UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, service name `iTantra`,
  64 KB max message.

### 9.7 HuggingFace download integrity — a trap we will hit

`ModelDownloadManager` documents why every HF-hosted download was silently failing its integrity
check: **HuggingFace's LFS `X-Linked-ETag` (the real content SHA-256) is set on the *redirect*
response from `huggingface.co`, not on the final CDN response OkHttp hands back after auto-following
it.** So `response.header("x-linked-etag")` is null on the terminal response, and the code silently
fell back to the CDN's own `etag` — *"a storage revision id, not a content hash."* Fix: walk the
whole redirect chain via `priorResponse`.

They also compare against a **previously-stored hash** per file (`ModelHashStore`), failing loudly if
a re-download differs from the copy already fetched — i.e. trust-on-first-use pinning on top of the
declared hash. Given we plan per-language on-demand fetch from HF, both of these are directly
applicable, and this pairs with the `.part` race and truncated-file bugs in §8.13.

### 9.8 `SocketTransport` — RTT/2 is wrong, and they say why

The previous implementation estimated latency as `latency/2` from `now − message.timestamp`. Their
comment: *"with unsynchronised clocks, `now − message.timestamp` mixes the peer's clock offset with
the one-way delay, it is not a round trip."* Replaced with the 3-timestamp NTP exchange in §9.3.
Worth checking whether our own peer-latency figure makes this mistake. Framing is a 4-byte length
prefix + Protobuf, 64 KB cap, 5 s ping interval, max 50 outstanding unACKed pings.

### 9.9 Measured latency evidence, and their self-corrections

`docs/latency-evidence/README.md` is the most methodologically careful document in the repo, largely
because it keeps correcting itself in public:

- **Phrase-level pipelining** (transcribe phrase 1 while the user is still speaking phrase 2) —
  measured head start of **+235 ms / −140 ms** cold, improving to **phrase 1 finishing 4.39 s before
  PTT release** once warm.
- **Cold vs warm dominates everything.** `tts_ms` (received → first audio) fell from **2.3 s / 4.7 s
  cold to 0.29 s / 0.27 s warm**; STT model load alone was **2,306 ms**. They warm both STT and TTS
  so the one-time load leaves the hot path, and note warming *"doesn't change inference speed."*
- Per-phrase STT ran **0.25–0.29× real time** warm.
- **Self-corrections they publish:** an earlier table wrongly attributed 2,439 ms to JIT when it was
  the model load inside `feature_ms`; `stt_ms` excludes queue wait (phrase 2 waited ~1.2 s);
  a previously-cited **1.2–5.0 s head start "was never captured to a committed artifact and should
  be treated as unverified"**; and Run 2's receiver was **a different physical device**, so
  receiver-side numbers "are not a clean apples-to-apples hardware comparison."
- An open, unconfirmed anomaly left visible: two phrases arrived 374 ms apart but synthesis finished
  only 92 ms apart.

This is the standard we will be measured against on evidence presentation. It is also a reminder that
our own 30-clip WER numbers need their caveats stated as plainly.

### 9.10 Smaller items worth knowing

- **`T43` — voice text in the language it is written in**, not the receiver's configured language.
  Their receiver was synthesising Tamil text with the Hindi voice. The fix means a Tamil message to a
  Hindi-only receiver now correctly produces **no audio** rather than a wrong-language
  mispronunciation — they chose the honest failure.
- **Foreground service** uses `FOREGROUND_SERVICE_TYPE_MICROPHONE` and requests a battery-optimisation
  whitelist to avoid OS termination; all state crosses to the UI via `StateFlow`/`SharedFlow` over a
  Binder, and the service never touches UI or the main thread.
- **Voice notes** (`VoiceNoteStore`) keep received messages replayable, deliberately off the playback
  path.
- **Their own quality gates**, from `validate_wer.py`: *"Hindi WER < 8%, Average Indic WER < 18%,
  INT8 regression gate: FP32 WER + 5% max."* Their own measured results (Hindi 10.00%, 8-language
  average 20.09%) **miss all three**. Useful calibration for what targets to state publicly — and
  note our multilingual Hindi 11.04% and mean 18.96% are in the same territory, so these are hard
  gates for everyone, not just them.
- `ModelDownloadManager` raises OkHttp's per-host dispatcher cap (§8.13) and skips re-downloading
  pieces already on disk.
- `CtcDecoder` is deliberately Android- and ONNX-free so it unit-tests without a Context or session —
  the same separation they used for `TdtDecoder`.

### Addendum 2 to the action checklist

- [ ] **Build a `Telemetry`-equivalent** (§9.3): NTP-style peer clock offset, monotonic→epoch
      conversion, `(senderId, sequence)` join key, per-utterance CSV, RTF excluding load and queue
      wait. This is the biggest gap between us and them.
- [ ] **Audit our mel front-end against all five items in §9.4** — especially `log_zero_guard = 2⁻²⁴`
      and `per_feature` normalization, whose absence produces the "same token repeated regardless of
      audio" failure.
- [ ] **Add a step-trace parity test** for any decoder we port, not just final-output comparison (§9.5).
- [ ] **Check whether our Wi-Fi Direct BUSY is two-owners-of-discovery** (§9.6) before adding retries.
- [ ] **Ensure something actually listens on the Bluetooth side** (§9.6) — a mutual-client deadlock
      looks exactly like "Bluetooth fallback doesn't work".
- [ ] **Add dual-transport dedup** with a timestamp-inclusive key (§9.6).
- [ ] **Walk the redirect chain for `X-Linked-ETag`** in our HF fetcher (§9.7).
- [ ] **Check our peer-latency metric isn't `RTT/2` across unsynchronised clocks** (§9.8).
- [ ] **Warm STT and TTS at startup** — cold load is 2.3 s and dominates first-message latency (§9.9).
- [ ] **Avoid CC-BY-NC MMS voices** for any language we intend to ship (§9.1).
- [ ] **Consider pulling SraVaani INT8 straight from `Chgauravpc/itantra`** (§9.2) rather than
      exporting it ourselves — verify provenance against `ARTPARK-IISc/SraVaani-1.0` first.

## 10. Resolved: MatMul-only INT8 beats both alternatives (measured 2026-09-27)

§8.2 left this as the top open question — whether restricting dynamic INT8 to MatMul, and leaving the
conv subsampling front-end in FP32, would escape both traps at once. It does. Measured on all 9 Indic
languages, 30 FLEURS clips each, CPU at 4 threads, same harness as §2:

| | mean WER | mean RTF | mean peak RAM | disk / language |
| --- | --- | --- | --- | --- |
| multilingual 628M FP32 (shipping today) | **18.96%** | 0.0997 | 3100 MB | 2381 MB (shared) |
| monolingual 120M FP32 | 19.24% | 0.0510 | 1407 MB | 459.7 MB |
| **monolingual 120M MatMul-only INT8** | **19.63%** | **0.0198** | **1102 MB** | **178.0 MB** |

Against the shared model the small INT8 model is **13.4× smaller on disk, 5.0× faster, 2.8× lighter in
RAM, for +0.67 WER points on average.** No language is significantly worse at 95% in a paired
bootstrap. Recipe: `quantize_dynamic(weight_type=QInt8, per_channel=True,
op_types_to_quantize=["MatMul"], extra_options={"MatMulConstBOnly": True})` — producing 0 ConvInteger
and 154 MatMulInteger nodes, 178.0 MB self-contained per language.

**The "INT8 is ~3× slower" finding does not generalise, and this is the important correction.** Their
measurement is sound but was taken on **VITS TTS**, which is Conv/ConvTranspose-heavy, where
`quantize_dynamic` rewrites convolutions to `ConvInteger` and ORT's CPU path for that is slow. A
**Conformer encoder is MatMul-dominated** — 205 MatMul against 54 Conv in this graph — so quantising
MatMul moves the bulk of the compute onto integer kernels and it comes out **2.6× faster than FP32**,
not slower. Their own ASR script still applies blanket `quantize_dynamic`, so they have not measured
this variant on their ASR path.

It also beats the weight-only scheme they settled on for the RAM-constrained case: weight-only
dequantises before compute, so the file shrinks while resident memory stays FP32-sized. MatMul-only
dynamic INT8 reduces file, RAM and time together.

Two honest caveats. Gujarati (+2.02) and Odia (+1.93) are the largest gaps and their CI lower bounds
sit just below zero (−0.46 and −0.86), so at 100 clips they could become significant — those two are
the ones to re-check before the tier is finalised. And peak RAM includes ~500 MB of desktop PyTorch
that an Android build never loads (`import torch` alone is +498 MB), so these are upper bounds and
still not on-device numbers.

## Source index

| Artifact | Why it matters |
| --- | --- |
| `docs/evaluation/sravaani/README.md` | T76: full SraVaani vs IndicConformer FLEURS eval, per-language WER/CER/RTF |
| `docs/evaluation/sravaani/tdt-engine-design.md` | TDT decode algorithm, tensor shapes, IO names, mel mismatch |
| `docs/evaluation/sravaani/phone/tdt/README.md` | Real on-phone RTF, PSS, load times, device models |
| `docs/TTS_QUANT_PLAN.md` | `ConvInteger` 3× slowdown, NNAPI deprecation, weight-only INT8 recipe |
| `docs/IMPROVEMENT_PLAN.md` | Their self-audit: 43.6× mel slowdown, claim-vs-code mismatches |
| `docs/KNOWN_ISSUES.md` | Truncated-model-download failure mode |
| `app/src/main/assets/MODELS_README.txt` | Real model inventory and sizes; Odia/TTS gaps |
| `app/src/main/proto/itantra.proto` | Wire format, ALERT override, src/dst lang |
| `/assets/index-CKTldBQJ.js` | All site copy, stat arrays, mesh tier table, provenance tags |
