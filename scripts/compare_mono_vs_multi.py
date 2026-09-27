"""Measured WER / RTF / RAM / disk comparison: AI4Bharat monolingual 120M vs multilingual 628M.

Decides whether a device-capability-tiered model strategy is viable for iTantra
(PS-26173): high-end phones load the shared 628M multilingual IndicConformer,
low/mid-end phones load a ~120M per-language model instead.

Each (model, language) pair runs in its own subprocess via ``--worker`` so the
peak-RSS figure belongs to that model alone. Both models run on CPU with a fixed
thread count, because the question is about phone-class compute.
"""

import argparse
import csv
import inspect
import json
import os
import subprocess
import sys
import threading
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

# Local FLEURS test set, pinned in evaluation/fleurs_test_100/DATASET.md.
DATASET = ROOT / "evaluation" / "fleurs_test_100"
MULTILINGUAL_DIR = ROOT / "deployment_models" / "indic" / "fp32"
# Same shared model, MatMul-only dynamic INT8 (see docs/competitor-itantra-teardown.md §10).
MULTILINGUAL_INT8_DIR = ROOT / "deployment_models" / "indic" / "matmul_int8"
MONOLINGUAL_REPO = "ai4bharat/indicconformer_stt_{lang}_hybrid_rnnt_large"
MONOLINGUAL_ONNX_REPO = "OpenVoiceOS/ai4bharat-indicconformer-{lang}-onnx"
HF_CACHE = ROOT / ".hfcache" / "hub"

# The nine Indic languages the project needs. English is excluded on purpose:
# no monolingual IndicConformer exists for it, and the project uses Whisper-tiny
# for English in both device tiers, so it is not part of this decision.
LANG_DIRS = {
    "hi": "hindi", "gu": "gujarati", "mr": "marathi", "kn": "kannada",
    "ml": "malayalam", "ta": "tamil", "te": "telugu", "or": "odia",
    "bn": "bengali",
}
THREADS = 4  # phone-class big-core count; keeps RTF comparable across models


# --- text normalization -------------------------------------------------------
# Identical to scripts/benchmark.py::normalize so these numbers stay comparable
# to this project's existing published WER figures.
def normalize(text):
    text = unicodedata.normalize("NFC", text).casefold()
    text = "".join(" " if unicodedata.category(char)[0] in "PS" else char for char in text)
    return " ".join(text.split())


NORMALIZATION_DOC = (
    "Unicode NFC, casefold (lowercase), every Unicode punctuation (P*) and symbol (S*) "
    "character replaced with a space, then whitespace collapsed and stripped. "
    "Indic combining marks are preserved. WER computed with jiwer.wer() on "
    "whitespace-separated tokens of the normalized strings."
)


def load_samples(lang, limit):
    """Deterministic sample list: manifest order filtered to one language."""
    rows = []
    with open(DATASET / "manifest.csv", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row["language_label"] == LANG_DIRS[lang]:
                rows.append(row)
    rows.sort(key=lambda row: row["audio_path"])
    return rows[:limit]


def directory_mib(path):
    path = Path(path)
    if path.is_file():
        return path.stat().st_size / 2**20
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file()) / 2**20


class PeakRSS:
    """Sample process RSS in a background thread.

    tracemalloc would miss ONNX Runtime and torch native allocations entirely,
    which is most of the footprint here, so measure real RSS instead.
    """

    def __init__(self, interval=0.05):
        import psutil

        self.proc = psutil.Process()
        self.interval = interval
        self.baseline = self.proc.memory_info().rss
        self.peak = self.baseline
        self._stop = False

    def _run(self):
        while not self._stop:
            try:
                self.peak = max(self.peak, self.proc.memory_info().rss)
            except Exception:
                return
            time.sleep(self.interval)

    def __enter__(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop = True
        self._thread.join(timeout=2)

    def mark(self):
        self.peak = max(self.peak, self.proc.memory_info().rss)


# --- model backends -----------------------------------------------------------
def build_multilingual(model_dir=None):
    """Project's own CTC path, forced onto CPU so RTF reflects phone compute."""
    import onnxruntime as ort
    import torch
    from sih_speech.pipeline import IndicCTCModel

    torch.set_num_threads(THREADS)
    options = ort.SessionOptions()
    options.intra_op_num_threads = THREADS

    real_session = ort.InferenceSession

    def cpu_session(path, providers=None, **kwargs):
        return real_session(path, sess_options=options, providers=["CPUExecutionProvider"])

    ort.InferenceSession = cpu_session
    try:
        # IndicCTCModel picks CUDA when torch reports it; force the CPU branch.
        cuda_available = torch.cuda.is_available
        torch.cuda.is_available = lambda: False
        try:
            model = IndicCTCModel(model_dir or MULTILINGUAL_DIR)
        finally:
            torch.cuda.is_available = cuda_available
    finally:
        ort.InferenceSession = real_session

    def transcribe(path, lang):
        from sih_speech.pipeline import load_audio

        with torch.inference_mode():
            return model(load_audio(path).unsqueeze(0), lang, "ctc")

    resolved = model_dir or MULTILINGUAL_DIR
    return transcribe, directory_mib(resolved), str(resolved)


def build_monolingual_onnx(lang, quantization=None):
    """ONNX export of the same AI4Bharat monolingual checkpoint.

    Chosen over the .nemo path because it is what would actually ship, it is not
    gated, and -- critically -- it runs on ONNX Runtime just like the multilingual
    model, so RTF compares two like runtimes instead of ONNX vs eager PyTorch.

    The export is CTC-only (``model_type: nemo-conformer-ctc``), matching the
    multilingual CTC path. Note this leaves the hybrid checkpoint's stronger RNNT
    head unmeasured, so these WERs are a conservative floor for the 120M model.
    """
    import numpy as np
    import onnx_asr
    import onnxruntime as ort
    import torch
    from sih_speech.pipeline import load_audio

    torch.set_num_threads(THREADS)
    repo = MONOLINGUAL_ONNX_REPO.format(lang=lang)
    options = ort.SessionOptions()
    options.intra_op_num_threads = THREADS
    if quantization == "matmul_int8":
        # Locally re-quantized: dynamic INT8 restricted to MatMul, leaving the conv subsampling
        # front-end in FP32. The upstream all-ops int8 export emits ConvInteger, which this
        # onnxruntime CPU build has no kernel for, so it cannot be loaded at all.
        local = ROOT / ".hfcache" / "matmul_int8" / lang
        model = onnx_asr.load_model("nemo-conformer-ctc", str(local),
                                    providers=["CPUExecutionProvider"], sess_options=options)
    else:
        model = onnx_asr.load_model(
            repo, quantization=quantization, providers=["CPUExecutionProvider"], sess_options=options
        )

    def transcribe(path, _lang):
        # onnx_asr's own file reader uses stdlib `wave`, which cannot decode the
        # float32 (format tag 3) WAVs in this corpus. Feed arrays through the
        # project's load_audio so both models see byte-identical input.
        waveform = load_audio(path).numpy().astype(np.float32)
        return model.recognize(waveform, sample_rate=16000)

    # Count only the variant actually loaded. onnx_asr fetches the whole repo,
    # which holds both fp32 and int8 copies, so summing every file would overstate
    # the fp32 footprint by the size of the int8 model and vice versa.
    stem = repo.split("/")[-1]
    if quantization == "matmul_int8":
        local = ROOT / ".hfcache" / "matmul_int8" / lang / "model.onnx"
        return transcribe, local.stat().st_size / 2**20, f"{local} (MatMul-only int8, conv fp32, CTC head)"
    wanted = ("model.int8.onnx",) if quantization == "int8" else ("model.onnx", "model.onnx_data")
    disk = 0.0
    for match in HF_CACHE.rglob("*"):
        if match.is_file() and stem in str(match) and match.name in wanted:
            disk += match.stat().st_size / 2**20
    return transcribe, disk, f"{repo} ({quantization or 'fp32'}, CTC head)"


def patch_tarfile_filter():
    """Let NeMo unpack .nemo archives on Python < 3.10.12.

    NeMo 3.0.0 calls ``tar.extract(member, path, filter="data")`` unconditionally,
    but the ``filter`` keyword (PEP 706) only appeared in 3.10.12; this interpreter
    is 3.10.11, so the call raises TypeError. NeMo already gates every member
    through its own ``is_safe_tar_member`` traversal check before extracting, so
    accepting and dropping the keyword does not remove that protection -- and the
    containment check below re-asserts it independently.
    """
    import tarfile

    if "filter" in inspect.signature(tarfile.TarFile.extract).parameters:
        return False

    original = tarfile.TarFile.extract

    def extract(self, member, path="", set_attrs=True, *, numeric_owner=False, filter=None):
        name = member.name if isinstance(member, tarfile.TarInfo) else member
        root = os.path.realpath(path or ".")
        target = os.path.realpath(os.path.join(root, name))
        if target != root and not target.startswith(root + os.sep):
            raise tarfile.TarError(f"refusing tar member outside destination: {name}")
        return original(self, member, path, set_attrs=set_attrs, numeric_owner=numeric_owner)

    tarfile.TarFile.extract = extract
    return True


def build_monolingual(lang, decoder):
    import torch

    if patch_tarfile_filter():
        print("  (shimmed tarfile.extract for Python 3.10.11: NeMo passes filter='data')", flush=True)

    import nemo.collections.asr as nemo_asr

    torch.set_num_threads(THREADS)
    repo = MONOLINGUAL_REPO.format(lang=lang)
    # ASRModel.from_pretrained fails on these checkpoints: NeMo leaves the
    # calling class as the abstract ASRModel base, so instantiation raises
    # "Can't instantiate abstract class". The archive's own model_config.yaml
    # declares target: ...hybrid_rnnt_ctc_bpe_models.EncDecHybridRNNTCTCBPEModel,
    # so load that concrete class directly.
    concrete = nemo_asr.models.EncDecHybridRNNTCTCBPEModel
    try:
        model = concrete.from_pretrained(model_name=repo, map_location="cpu")
    except TypeError:
        model = nemo_asr.models.ASRModel.from_pretrained(model_name=repo, map_location="cpu")
    model.freeze()
    model.eval()
    if hasattr(model, "cur_decoder"):
        model.cur_decoder = decoder

    def transcribe(path, _lang):
        kwargs = {"batch_size": 1, "verbose": False}
        try:
            out = model.transcribe([str(path)], language_id=lang, **kwargs)
        except TypeError:
            out = model.transcribe([str(path)], **kwargs)
        item = out[0]
        if isinstance(item, (list, tuple)):  # hybrid models can return (ctc, rnnt)
            item = item[0]
        return getattr(item, "text", item)

    # Locate the downloaded .nemo for an honest on-disk figure.
    disk, source = 0.0, repo
    candidates = list(HF_CACHE.rglob("*.nemo")) if HF_CACHE.exists() else []
    nemo_cache = Path(os.environ.get("NEMO_CACHE_DIR", os.path.expanduser("~/.cache/torch/NeMo")))
    if nemo_cache.exists():
        candidates += list(nemo_cache.rglob("*.nemo"))
    for candidate in candidates:
        size = candidate.stat().st_size / 2**20
        if lang in candidate.name and size > disk:
            disk, source = size, str(candidate)
    params = sum(parameter.numel() for parameter in model.parameters())
    return transcribe, disk, f"{source} ({params / 1e6:.0f}M params)"


# --- worker -------------------------------------------------------------------
def run_worker(args):
    import jiwer
    import soundfile as sf

    samples = load_samples(args.language, args.samples)
    tracker = PeakRSS()
    with tracker:
        load_start = time.perf_counter()
        if args.model == "multilingual_628M_matmul_int8":
            transcribe, disk_mib, source = build_multilingual(MULTILINGUAL_INT8_DIR)
        elif args.model == "multilingual_628M":
            transcribe, disk_mib, source = build_multilingual()
        elif args.model == "monolingual_120M_nemo":
            transcribe, disk_mib, source = build_monolingual(args.language, args.decoder)
        else:
            quantization = ("matmul_int8" if args.model.endswith("matmul_int8")
                            else "int8" if args.model.endswith("int8") else None)
            transcribe, disk_mib, source = build_monolingual_onnx(args.language, quantization)
        load_seconds = time.perf_counter() - load_start
        tracker.mark()

        # Warm-up on the first clip; excluded from timing so lazy init and ORT
        # arena growth do not inflate the first sample's RTF.
        warm = DATASET / samples[0]["audio_path"].replace("\\", "/")
        try:
            transcribe(warm, args.language)
        except Exception as exc:
            print(f"  warm-up failed: {type(exc).__name__}: {exc}", flush=True)

        records, audio_seconds, infer_seconds = [], 0.0, 0.0
        for index, row in enumerate(samples, 1):
            path = DATASET / row["audio_path"].replace("\\", "/")
            duration = sf.info(str(path)).duration
            start = time.perf_counter()
            hypothesis = transcribe(path, args.language)
            elapsed = time.perf_counter() - start
            tracker.mark()
            audio_seconds += duration
            infer_seconds += elapsed
            records.append({
                "fleurs_id": row["fleurs_id"],
                "audio_path": row["audio_path"],
                "audio_seconds": round(duration, 3),
                "infer_seconds": round(elapsed, 3),
                "reference": row["transcript"],
                "hypothesis": hypothesis,
                "reference_normalized": normalize(row["transcript"]),
                "hypothesis_normalized": normalize(hypothesis),
            })
            if index % 5 == 0 or index == len(samples):
                print(f"  [{args.model}/{args.language}] {index}/{len(samples)}", flush=True)

    references = [record["reference_normalized"] for record in records]
    hypotheses = [record["hypothesis_normalized"] for record in records]
    # jiwer raises on an empty reference, so drop those pairs and report the count.
    usable = [(ref, hyp) for ref, hyp in zip(references, hypotheses) if ref.strip()]
    wer = jiwer.wer([ref for ref, _ in usable], [hyp for _, hyp in usable])
    empty_hypotheses = sum(1 for hyp in hypotheses if not hyp.strip())

    result = {
        "WER": round(wer * 100, 2),
        "WER_fraction": round(wer, 5),
        "RTF": round(infer_seconds / audio_seconds, 4),
        "peak_RAM_MB": round(tracker.peak / 2**20, 1),
        "baseline_RAM_MB": round(tracker.baseline / 2**20, 1),
        "incremental_RAM_MB": round((tracker.peak - tracker.baseline) / 2**20, 1),
        "disk_MB": round(disk_mib, 1),
        "samples": len(records),
        "scored_samples": len(usable),
        "empty_hypotheses": empty_hypotheses,
        "audio_seconds": round(audio_seconds, 2),
        "infer_seconds": round(infer_seconds, 2),
        "model_load_seconds": round(load_seconds, 2),
        "decoder": args.decoder if args.model == "monolingual_120M_nemo" else "ctc",
        "source": source,
        "device": f"cpu ({THREADS} threads)",
    }
    payload = {"summary": result, "records": records}
    Path(args.out).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0


# --- driver -------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", default=",".join(LANG_DIRS))
    parser.add_argument("--models", default="monolingual_120M,multilingual_628M")
    parser.add_argument("--samples", type=int, default=25)
    parser.add_argument("--decoder", default="rnnt", choices=["rnnt", "ctc"])
    parser.add_argument("--out", default=str(ROOT / "wer_comparison_results.json"))
    parser.add_argument("--partials", default=str(ROOT / "evaluation" / "mono_vs_multi_partials"))
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--model")
    parser.add_argument("--language")
    args = parser.parse_args()

    if args.worker:
        return run_worker(args)

    languages = [item.strip() for item in args.languages.split(",") if item.strip()]
    models = [item.strip() for item in args.models.split(",") if item.strip()]
    partial_dir = Path(args.partials)
    partial_dir.mkdir(parents=True, exist_ok=True)

    results, failures = {}, {}
    for language in languages:
        results[language] = {}
        for model in models:
            partial = partial_dir / f"{model}_{language}.json"
            print(f"\n=== {model} / {language} ({args.samples} samples) ===", flush=True)
            command = [
                sys.executable, str(Path(__file__).resolve()), "--worker",
                "--model", model, "--language", language,
                "--samples", str(args.samples), "--decoder", args.decoder,
                "--out", str(partial),
            ]
            environment = dict(os.environ)
            # Redirect only the hub cache: overriding HF_HOME would move token
            # discovery off ~/.cache/huggingface/token and break gated access.
            environment["HF_HUB_CACHE"] = str(HF_CACHE)
            environment["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
            environment["NEMO_CACHE_DIR"] = str(ROOT / ".hfcache" / "nemo")
            environment["OMP_NUM_THREADS"] = str(THREADS)
            completed = subprocess.run(command, env=environment)
            if completed.returncode != 0 or not partial.exists():
                failures[f"{model}/{language}"] = f"worker exited {completed.returncode}"
                results[language][model] = {"error": f"worker exited {completed.returncode}"}
                continue
            results[language][model] = json.loads(partial.read_text(encoding="utf-8"))["summary"]

    payload = {
        "task": "iTantra PS-26173 device-tier model decision: monolingual 120M vs multilingual 628M",
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "dataset": {
            "name": "google/fleurs test split (local pinned copy)",
            "path": str(DATASET.relative_to(ROOT)),
            "revision": "70bb2e84b976b7e960aa89f1c648e09c59f894dd",
            "samples_per_language": args.samples,
            "selection": "manifest.csv rows for the language, sorted by audio_path, first N (deterministic)",
            "note": "Read speech. Kathbath/IndicVoices audio is not present in this project, so FLEURS was used.",
        },
        "normalization": NORMALIZATION_DOC,
        "metrics": {
            "WER": "percent, jiwer.wer() over normalized whitespace tokens, corpus-level",
            "RTF": "sum(per-sample inference seconds) / sum(audio seconds); excludes model load and audio decode",
            "peak_RAM_MB": "peak process RSS sampled at 50 ms across model load + inference, one model per process",
            "disk_MB": "on-disk size of the weights actually loaded",
        },
        "caveats": [
            "RTF is not a pure architecture comparison: the multilingual model runs as ONNX Runtime "
            "graphs (operator-fused, release-optimized) while the monolingual model runs as eager "
            "PyTorch/NeMo. WER is unaffected by this; RTF favours the multilingual runtime.",
            "Decoders differ: multilingual is CTC with a per-language vocabulary mask (the only path "
            "this project ships); the monolingual hybrid is decoded with its own head.",
            f"CPU-only, {THREADS} threads, to approximate phone-class compute rather than the dev GPU.",
        ],
        "results": results,
    }
    Path(args.out).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n" + "=" * 78)
    print(f"{'language':>9} {'model':>20} {'WER%':>8} {'RTF':>8} {'peakRAM_MB':>12} {'disk_MB':>10}")
    print("-" * 78)
    for language, per_model in results.items():
        for model, values in per_model.items():
            if "error" in values:
                print(f"{language:>9} {model:>20} {'FAILED':>8}  {values['error']}")
            else:
                print(f"{language:>9} {model:>20} {values['WER']:>8.2f} {values['RTF']:>8.3f} "
                      f"{values['peak_RAM_MB']:>12.1f} {values['disk_MB']:>10.1f}")
    print("=" * 78)
    print(f"\nWrote {args.out}")
    if failures:
        print("\nFAILURES:")
        for key, message in failures.items():
            print(f"  {key}: {message}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
