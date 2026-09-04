"""Core offline VoxLingua-to-IndicConformer/Whisper speech pipeline."""

import argparse
import csv
import importlib.util
import json
import os
import re
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from scipy.signal import resample_poly
from torch import nn

LANGUAGES = (
    "hindi", "gujarati", "marathi", "kannada", "malayalam",
    "tamil", "telugu", "odia", "bengali", "english",
)
INDIC_CODES = {
    "hindi": "hi", "gujarati": "gu", "marathi": "mr",
    "kannada": "kn", "malayalam": "ml", "tamil": "ta",
    "telugu": "te", "odia": "or", "bengali": "bn",
}
ALIASES = {**{name: name for name in LANGUAGES}, **dict(zip(INDIC_CODES.values(), INDIC_CODES)), "en": "english"}
LID_ID = "speechbrain/lang-id-voxlingua107-ecapa"
INDIC_ID = "ai4bharat/indic-conformer-600m-multilingual"
WHISPER_ID = "openai/whisper-tiny"


def restrict_log_probabilities(log_probabilities, indices):
    return log_probabilities[indices].softmax(dim=0)


def load_audio(path, sample_rate=16000):
    audio, rate = sf.read(path, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if rate != sample_rate:
        audio = resample_poly(audio, sample_rate, rate).astype("float32")
    return torch.from_numpy(audio)


def download_models(root):
    from huggingface_hub import snapshot_download

    root = Path(root)
    snapshot_download(LID_ID, local_dir=root / "lid")
    snapshot_download(
        WHISPER_ID,
        local_dir=root / "whisper",
        ignore_patterns=["*.h5", "*.msgpack", "pytorch_model.bin"],
    )
    snapshot_download(INDIC_ID, local_dir=root / "indic")
    print(f"Models downloaded to {root.resolve()}; inference can now run offline.")


def read_manifest(path, require_all=False):
    manifest = Path(path)
    rows = []
    with manifest.open(newline="", encoding="utf-8") as handle:
        for line, row in enumerate(csv.DictReader(handle), 2):
            try:
                label = ALIASES[row["language_label"].strip().lower()]
                audio_path = Path(row["audio_path"])
            except (KeyError, AttributeError) as error:
                raise ValueError(f"Invalid manifest row {line}: {row}") from error
            if not audio_path.is_absolute():
                audio_path = manifest.parent / audio_path
            if not audio_path.is_file():
                raise FileNotFoundError(f"Manifest row {line}: {audio_path}")
            rows.append((audio_path, label, row.get("transcript", "")))
    if not rows:
        raise ValueError("Manifest is empty")
    missing = set(LANGUAGES) - {label for _, label, _ in rows}
    if require_all and missing:
        raise ValueError(f"Manifest needs at least one sample per language; missing: {sorted(missing)}")
    return rows


def speechbrain_imports():
    import torchaudio

    if not hasattr(torchaudio, "list_audio_backends"):
        torchaudio.list_audio_backends = lambda: ["soundfile"]
    from speechbrain.lobes.features import Fbank
    from speechbrain.lobes.models.ECAPA_TDNN import ECAPA_TDNN
    from speechbrain.lobes.models.Xvector import Classifier
    from speechbrain.processing.features import InputNormalization
    return Fbank, ECAPA_TDNN, Classifier, InputNormalization


class FrozenECAPA(nn.Module):
    def __init__(self, model_dir, device):
        super().__init__()
        Fbank, ECAPA_TDNN, _, InputNormalization = speechbrain_imports()
        self.features = Fbank(n_mels=60, left_frames=0, right_frames=0, deltas=False)
        self.normalize = InputNormalization(norm_type="sentence", std_norm=False)
        self.embedding = ECAPA_TDNN(
            input_size=60,
            channels=[1024, 1024, 1024, 1024, 3072],
            kernel_sizes=[5, 3, 3, 3, 1],
            dilations=[1, 2, 3, 4, 1],
            attention_channels=128,
            lin_neurons=256,
        )
        weights = torch.load(Path(model_dir) / "embedding_model.ckpt", map_location="cpu", weights_only=True)
        self.embedding.load_state_dict(weights)
        self.requires_grad_(False).eval().to(device)

    def forward(self, waveforms, lengths):
        return self.embedding(self.normalize(self.features(waveforms), lengths), lengths).squeeze(1)


def load_vox_classifier(model_dir, device):
    _, _, Classifier, _ = speechbrain_imports()
    classifier = Classifier(
        input_shape=[None, None, 256], activation=nn.LeakyReLU,
        lin_blocks=1, lin_neurons=512, out_neurons=107,
    )
    weights = torch.load(Path(model_dir) / "classifier.ckpt", map_location="cpu", weights_only=True)
    classifier.load_state_dict(weights)
    return classifier.requires_grad_(False).eval().to(device)


def read_vox_indices(model_dir):
    indices = {}
    pattern = re.compile(r"^'([^:]+):.*' => (\d+)$")
    for line in (Path(model_dir) / "label_encoder.txt").read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match:
            indices[match.group(1)] = int(match.group(2))
    required = {"en", *(code for name, code in INDIC_CODES.items() if name != "odia")}
    if missing := required - indices.keys():
        raise ValueError(f"VoxLingua label map is missing expected labels: {sorted(missing)}")
    if "or" in indices:
        raise ValueError("VoxLingua now includes Odia; revisit the coarse-routing assumption")
    return indices


class VoxLinguaCoarseLID:
    """Original 107-way model, restricted to English and its eight covered target Indic labels."""

    # Personalized-calibration finding (see conversation / evaluation/routing_calibration):
    # for Indian-accented English speakers, VoxLingua107's "english" score often does not
    # win the argmax even on genuine English speech (it wasn't trained on this accent), but
    # it still tends to sit measurably above the near-zero noise floor seen on genuine Indic
    # speech (real-mic test: Indic clips' english score maxed at 0.0033; English clips ranged
    # 0.00007-0.72, most above 0.01). A small fixed threshold on the english score alone --
    # instead of pure argmax over all 9 labels -- recovered most real-mic English clips
    # (0% -> ~73% correct) with only a single flip on the 300-sample FLEURS calibration set
    # (still ~99.7% there). Tuned by grid search across both datasets combined; see
    # evaluation/routing_calibration/ for the sweep.
    DEFAULT_ENGLISH_THRESHOLD = 0.005

    def __init__(self, model_dir, device, english_threshold=DEFAULT_ENGLISH_THRESHOLD):
        self.device = device
        self.english_threshold = english_threshold
        self.extractor = FrozenECAPA(model_dir, device)
        self.classifier = load_vox_classifier(model_dir, device)
        indices = read_vox_indices(model_dir)
        self.labels = ["english", *(name for name in INDIC_CODES if name != "odia")]
        codes = ["en", *(INDIC_CODES[name] for name in self.labels[1:])]
        self.indices = torch.tensor([indices[code] for code in codes], device=device)

    def predict(self, audio_path):
        waveform = load_audio(audio_path).unsqueeze(0).to(self.device)
        start = time.perf_counter()
        with torch.inference_mode():
            embedding = self.extractor(waveform, torch.ones(1, device=self.device))
            log_probabilities = self.classifier(embedding.unsqueeze(1))[0, 0]
            restricted = restrict_log_probabilities(log_probabilities, self.indices)
        elapsed_ms = (time.perf_counter() - start) * 1000
        index = restricted.argmax().item()
        legacy_language = self.labels[index]  # unchanged: raw argmax, kept for the legacy-Vox metric
        english_score = restricted[0].item()
        coarse_group = "english" if english_score > self.english_threshold else "indic"
        return {
            "coarse_group": coarse_group,
            "legacy_language": legacy_language,
            "confidence": english_score if coarse_group == "english" else restricted[index].item(),
            "scores": dict(zip(self.labels, restricted.tolist())),
            "latency_ms": elapsed_ms,
        }


def load_indic_model(model_dir):
    import onnxruntime as ort

    module_path = Path(model_dir) / "model_onnx.py"
    spec = importlib.util.spec_from_file_location("local_indic_conformer", module_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    has_ort_cuda = "CUDAExecutionProvider" in ort.get_available_providers()
    cuda_available = module.torch.cuda.is_available
    if not has_ort_cuda:
        module.torch.cuda.is_available = lambda: False
    try:
        return module.IndicASRModel(module.IndicASRConfig(ts_folder=str(Path(model_dir).resolve())))
    finally:
        module.torch.cuda.is_available = cuda_available


class IndicASR:
    def __init__(self, model_dir):
        self.model = load_indic_model(model_dir)

    def _decode_language(self, logits, global_log_probs, language):
        code = INDIC_CODES[language]
        mask = torch.tensor(self.model.language_masks[code], dtype=torch.bool)
        global_indices = torch.where(mask)[0]
        local_log_probs = logits[:, mask].log_softmax(-1)
        path = local_log_probs.argmax(-1)
        starts = torch.cat((torch.tensor([0]), torch.where(path[1:] != path[:-1])[0] + 1))
        ends = torch.cat((starts[1:], torch.tensor([len(path)])))
        tokens, confidences = [], []
        for start, end in zip(starts.tolist(), ends.tolist()):
            local_id = path[start].item()
            if local_id == self.model.config.BLANK_ID:
                continue
            tokens.append(self.model.vocab[code][local_id])
            global_id = global_indices[local_id]
            confidences.append(global_log_probs[start:end, global_id].exp().max().item())
        text = "".join(tokens).replace("▁", " ").strip()
        return {
            "language": language,
            "language_code": code,
            "text": text,
            "confidence": float(np.mean(confidences)) if confidences else 0.0,
            "token_count": len(tokens),
        }

    def transcribe_all(self, audio_path):
        waveform = load_audio(audio_path).unsqueeze(0)
        start = time.perf_counter()
        encoder_outputs, encoded_lengths = self.model.encode(waveform)
        encoder_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        raw_logits = self.model.models["ctc_decoder"].run(
            ["logprobs"], {"encoder_output": encoder_outputs}
        )[0]
        decoder_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        logits = torch.from_numpy(raw_logits[0, :int(encoded_lengths[0])])
        global_log_probs = logits.log_softmax(-1)
        candidates = [
            self._decode_language(logits, global_log_probs, language)
            for language in INDIC_CODES
        ]
        voting_ms = (time.perf_counter() - start) * 1000
        winner = max(candidates, key=lambda item: item["confidence"])
        return {
            **winner,
            "model": INDIC_ID,
            "encoder_ms": encoder_ms,
            "ctc_decoder_ms": decoder_ms,
            "nine_way_voting_ms": voting_ms,
            "indic_total_ms": encoder_ms + decoder_ms + voting_ms,
            "candidates": candidates,
        }


class WhisperASR:
    def __init__(self, model_dir, device):
        from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

        self.device = device
        self.processor = AutoProcessor.from_pretrained(model_dir, local_files_only=True)
        self.model = AutoModelForSpeechSeq2Seq.from_pretrained(model_dir, local_files_only=True).to(device).eval()

    def transcribe(self, audio_path):
        audio = load_audio(audio_path).numpy()
        inputs = self.processor(audio, sampling_rate=16000, return_tensors="pt", return_attention_mask=True)
        inputs = {key: value.to(self.device) for key, value in inputs.items()}
        with torch.inference_mode():
            output = self.model.generate(
                **inputs, language="en", task="transcribe",
                output_scores=True, return_dict_in_generate=True,
            )
        text = self.processor.batch_decode(output.sequences, skip_special_tokens=True)[0].strip()
        return {"text": text, "confidence": whisper_confidence(output)}


def whisper_confidence(generate_output):
    """Mean per-token probability of the tokens Whisper actually emitted."""
    if not generate_output.scores:
        return 0.0
    step_log_probs = torch.stack(generate_output.scores, dim=1).log_softmax(-1)
    generated_tokens = generate_output.sequences[:, -step_log_probs.shape[1]:]
    token_log_probs = step_log_probs.gather(-1, generated_tokens.unsqueeze(-1)).squeeze(-1)
    return token_log_probs.exp().mean().item()


class SpeechPipeline:
    def __init__(self, model_root, device=None, indic_dir=None, lid_confidence_threshold=0.0, use_lid=True):
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.lid_confidence_threshold = lid_confidence_threshold
        self.use_lid = use_lid
        root = Path(model_root)
        self.lid = VoxLinguaCoarseLID(root / "lid", self.device) if use_lid else None
        self.indic = IndicASR(indic_dir or root / "indic")
        self.whisper = WhisperASR(root / "whisper", self.device)

    def _english_result(self, whisper_result, decision):
        return {
            "language": "english", "text": whisper_result["text"],
            "model": WHISPER_ID, "indic_total_ms": 0.0, "candidates": [],
            "asr_confidence": whisper_result["confidence"], "decision": decision,
        }

    def _indic_result(self, indic_result, decision):
        return {**indic_result, "asr_confidence": indic_result["confidence"], "decision": decision}

    def _cross_confidence_fallback(self, audio_path, decision_prefix, routing):
        # NOTE: Whisper's mean per-token probability and IndicConformer's mean
        # CTC-token probability are not calibrated to the same scale (Whisper's
        # autoregressive LM head tends to stay confidently fluent even when the
        # acoustic content is wrong). This comparison is a stopgap, not a
        # validated decision rule -- see the calibration task before trusting
        # it as the sole router. Prefer keeping LID until that's done.
        whisper_result = self.whisper.transcribe(audio_path)
        indic_result = self.indic.transcribe_all(audio_path)
        if whisper_result["confidence"] >= indic_result["confidence"]:
            return {**self._english_result(whisper_result, f"{decision_prefix}-whisper-won"), **routing}
        return {**self._indic_result(indic_result, f"{decision_prefix}-indic-won"), **routing}

    def transcribe(self, audio_path):
        if not self.use_lid:
            no_lid_routing = {
                "legacy_language": None, "lid_confidence": None,
                "lid_scores": {}, "lid_latency_ms": 0.0,
            }
            return self._cross_confidence_fallback(audio_path, "no-lid-uncalibrated", no_lid_routing)

        lid = self.lid.predict(audio_path)
        routing = {
            "legacy_language": lid["legacy_language"],
            "lid_confidence": lid["confidence"],
            "lid_scores": lid["scores"],
            "lid_latency_ms": lid["latency_ms"],
        }

        # Calibrated on 300 real gold-labeled FLEURS samples (all 10 languages,
        # evaluation/routing_calibration/records.json): trusting LID's own coarse
        # pick unconditionally scored 100% coarse-routing accuracy, beating both
        # the earlier confidence-threshold+fallback design (94.3%, 17 misroutes --
        # it second-guessed correct LID calls using an uncalibrated cross-model
        # signal) and raw Whisper-vs-Indic confidence comparison with no LID at
        # all (30.3% -- Whisper's confidence is structurally inflated and wins
        # almost every comparison regardless of correctness). Default threshold
        # is 0.0 so this branch is always taken. NOTE: this calibration set is
        # full natural-length utterances; short/noisy real-time clips (duration
        # slicing showed VoxLingua confidence can still collapse under ~2s) are
        # NOT covered by this calibration and remain an open risk -- raising
        # lid_confidence_threshold re-enables the (unvalidated) fallback below
        # for that regime, but do not trust it without new calibration data.
        if lid["confidence"] >= self.lid_confidence_threshold:
            decision = f"lid-confident({lid['confidence']:.2f})"
            if lid["coarse_group"] == "english":
                return {**self._english_result(self.whisper.transcribe(audio_path), decision), **routing}
            return {**self._indic_result(self.indic.transcribe_all(audio_path), decision), **routing}

        return self._cross_confidence_fallback(audio_path, f"lid-ambiguous({lid['confidence']:.2f})", routing)


def evaluate(manifest, pipeline, output=None):
    rows = read_manifest(manifest, require_all=True)
    details, stats = [], defaultdict(lambda: {"samples": 0, "legacy_correct": 0, "parallel_correct": 0})
    for audio_path, expected, transcript in rows:
        result = pipeline.transcribe(audio_path)
        row = {"audio": str(audio_path), "expected": expected, "reference": transcript, **result}
        details.append(row)
        item = stats[expected]
        item["samples"] += 1
        item["legacy_correct"] += result["legacy_language"] == expected
        item["parallel_correct"] += result["language"] == expected
        if expected == "odia":
            print("ODIA", json.dumps({
                "audio": str(audio_path), "voxlingua_guess": result["legacy_language"],
                "parallel_guess": result["language"], "transcription": result["text"],
            }, ensure_ascii=False))
    print("\n| language | samples | Vox restricted accuracy | parallel-decode accuracy |")
    print("|---|---:|---:|---:|")
    for language in LANGUAGES:
        item = stats[language]
        print(
            f"| {language} | {item['samples']} | "
            f"{item['legacy_correct'] / item['samples']:.3f} | "
            f"{item['parallel_correct'] / item['samples']:.3f} |"
        )
    indic_rows = [row for row in details if row["expected"] != "english"]
    lid_ms = float(np.median([row["lid_latency_ms"] for row in indic_rows]))
    added_ms = float(np.median([row["indic_total_ms"] for row in indic_rows]))
    summary = {
        "median_voxlingua_ms": lid_ms,
        "median_shared_encoder_ms": float(np.median([row["encoder_ms"] for row in indic_rows])),
        "median_shared_ctc_decoder_ms": float(np.median([row["ctc_decoder_ms"] for row in indic_rows])),
        "median_nine_mask_voting_ms": float(np.median([row["nine_way_voting_ms"] for row in indic_rows])),
        "median_parallel_decode_added_ms": added_ms,
        "median_total_router_ms": lid_ms + added_ms,
        "router_slowdown": (lid_ms + added_ms) / lid_ms,
    }
    print("\nLatency:", json.dumps(summary, indent=2))
    report = {"per_language": dict(stats), "latency": summary, "samples": details}
    if output:
        Path(output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def add_runtime_arguments(parser):
    parser.add_argument("--model-root", default="models")
    parser.add_argument("--indic-dir")
    parser.add_argument("--device")
    parser.add_argument("--lid-threshold", type=float, default=0.0)
    parser.add_argument("--no-lid", action="store_true", help="skip LID; route by cross-model ASR confidence only")


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    download = subparsers.add_parser("download")
    download.add_argument("--model-root", default="models")
    transcribe = subparsers.add_parser("transcribe")
    transcribe.add_argument("--audio", required=True)
    add_runtime_arguments(transcribe)
    comparison = subparsers.add_parser("compare")
    comparison.add_argument("--manifest", required=True)
    comparison.add_argument("--output", default="routing_comparison.json")
    add_runtime_arguments(comparison)
    args = parser.parse_args()
    if args.command == "download":
        download_models(args.model_root)
        return
    pipeline = SpeechPipeline(args.model_root, args.device, args.indic_dir, args.lid_threshold, not args.no_lid)
    if args.command == "transcribe":
        print(json.dumps(pipeline.transcribe(args.audio), ensure_ascii=False, indent=2))
    else:
        evaluate(args.manifest, pipeline, args.output)


if __name__ == "__main__":
    main()
