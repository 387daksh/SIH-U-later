"""Core offline VoxLingua-to-IndicConformer/Whisper speech pipeline."""

import argparse
import csv
import json
import os
import re
import time
from collections import defaultdict
from pathlib import Path
from types import SimpleNamespace

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
INDIC_FRAME_SECONDS = 0.08


def restrict_log_probabilities(log_probabilities, indices):
    return log_probabilities[indices].softmax(dim=0)


def load_audio(path, sample_rate=16000):
    source = path
    if isinstance(path, (str, os.PathLike)):
        source = Path(path)
        if not source.is_file():
            raise FileNotFoundError(f"Audio file not found: {source}")
    audio, rate = sf.read(source, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if not len(audio) or not np.isfinite(audio).all():
        raise ValueError(f"Audio is empty or contains non-finite samples: {path}")
    if rate != sample_rate:
        audio = resample_poly(audio, sample_rate, rate).astype("float32")
    if len(audio) < sample_rate // 10:
        raise ValueError(f"Audio is shorter than 100 ms: {path}")
    if float(np.max(np.abs(audio))) <= 1e-7:
        raise ValueError(f"Audio contains digital silence: {path}")
    return torch.from_numpy(audio)


def dynamic_int8(model, device):
    if device != "cpu":
        raise ValueError("PyTorch dynamic INT8 is supported only for CPU in this pipeline")
    return torch.ao.quantization.quantize_dynamic(model, {nn.Linear}, dtype=torch.qint8)


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


def ecapa_utterance_embedding(extractor, waveform, device, chunk_samples=48_000):
    """Average 3-second ECAPA chunks, matching the pretrained recipe's segment length."""
    chunks = list(waveform.split(chunk_samples))
    longest = max(map(len, chunks))
    batch = torch.zeros(len(chunks), longest, dtype=waveform.dtype, device=device)
    lengths = torch.tensor([len(chunk) / longest for chunk in chunks], device=device)
    for index, chunk in enumerate(chunks):
        batch[index, :len(chunk)] = chunk.to(device)
    return extractor(batch, lengths).mean(0)


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
    """Frozen VoxLingua ECAPA with either its legacy classifier or a binary head."""

    def __init__(self, model_dir, device, quantize=False, binary_head=None):
        self.device = device
        self.extractor = FrozenECAPA(model_dir, device)
        self.binary_head = None
        if binary_head:
            self.binary_head = torch.load(binary_head, map_location=device, weights_only=True)
            if self.binary_head.get("format_version") != 1:
                raise ValueError(f"Unsupported binary LID head: {binary_head}")
            self.classifier = None
            self.labels = ["english", "indic"]
        else:
            self.classifier = load_vox_classifier(model_dir, device)
        if quantize:
            self.extractor = dynamic_int8(self.extractor, device)
            if self.classifier:
                self.classifier = dynamic_int8(self.classifier, device)
        if not self.binary_head:
            indices = read_vox_indices(model_dir)
            self.labels = ["english", *(name for name in INDIC_CODES if name != "odia")]
            codes = ["en", *(INDIC_CODES[name] for name in self.labels[1:])]
            self.indices = torch.tensor([indices[code] for code in codes], device=device)

    def predict(self, audio_path):
        waveform = load_audio(audio_path).unsqueeze(0).to(self.device)
        start = time.perf_counter()
        with torch.inference_mode():
            if self.binary_head:
                embedding = ecapa_utterance_embedding(self.extractor, waveform[0], self.device)
                normalized = (embedding - self.binary_head["mean"]) / self.binary_head["scale"]
                english = torch.sigmoid(normalized @ self.binary_head["weight"] + self.binary_head["bias"])
                restricted = torch.stack((english, 1 - english))
            else:
                embedding = self.extractor(waveform, torch.ones(1, device=self.device))
                log_probabilities = self.classifier(embedding.unsqueeze(1))[0, 0]
                restricted = restrict_log_probabilities(log_probabilities, self.indices)
        elapsed_ms = (time.perf_counter() - start) * 1000
        index = restricted.argmax().item()
        legacy_language = self.labels[index]
        return {
            "coarse_group": "english" if legacy_language == "english" else "indic",
            "legacy_language": legacy_language,
            "confidence": restricted[index].item(),
            "scores": dict(zip(self.labels, restricted.tolist())),
            "latency_ms": elapsed_ms,
        }


class IndicCTCModel:
    """Minimal loader for the only IndicConformer path used by this pipeline."""

    def __init__(self, model_dir):
        import onnxruntime as ort

        root = Path(model_dir).resolve()
        assets = root / "assets"
        required = [
            root / "config.json",
            assets / "preprocessor.ts",
            assets / "encoder.onnx",
            assets / "ctc_decoder.onnx",
            assets / "vocab.json",
            assets / "language_masks.json",
        ]
        if missing := [str(path) for path in required if not path.is_file()]:
            raise FileNotFoundError(f"Incomplete IndicConformer directory; missing: {missing}")

        config = json.loads((root / "config.json").read_text(encoding="utf-8"))
        available = ort.get_available_providers()
        use_cuda = torch.cuda.is_available() and "CUDAExecutionProvider" in available
        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"] if use_cuda else ["CPUExecutionProvider"]
        self.d = torch.device("cuda" if use_cuda else "cpu")
        self.config = SimpleNamespace(BLANK_ID=int(config.get("BLANK_ID", 256)))
        self.models = {
            "preprocessor": torch.jit.load(str(assets / "preprocessor.ts"), map_location=self.d),
            "encoder": ort.InferenceSession(str(assets / "encoder.onnx"), providers=providers),
            "ctc_decoder": ort.InferenceSession(str(assets / "ctc_decoder.onnx"), providers=providers),
        }
        self.vocab = json.loads((assets / "vocab.json").read_text(encoding="utf-8"))
        self.language_masks = json.loads((assets / "language_masks.json").read_text(encoding="utf-8"))

    def encode(self, waveform):
        length = torch.tensor([waveform.shape[-1]], device=self.d)
        audio_signal, length = self.models["preprocessor"](
            input_signal=waveform.to(self.d), length=length
        )
        return self.models["encoder"].run(
            ["outputs", "encoded_lengths"],
            {"audio_signal": audio_signal.cpu().numpy(), "length": length.cpu().numpy()},
        )

    def __call__(self, waveform, language, decoding="ctc"):
        if decoding != "ctc":
            raise ValueError("The SIH pipeline loads only the CTC decoder")
        encoder_outputs, encoded_lengths = self.encode(waveform)
        raw_logits = self.models["ctc_decoder"].run(
            ["logprobs"], {"encoder_output": encoder_outputs}
        )[0]
        logits = torch.from_numpy(raw_logits[0, :int(encoded_lengths[0])])
        mask = torch.tensor(self.language_masks[language], dtype=torch.bool)
        path = torch.unique_consecutive(logits[:, mask].argmax(-1))
        return "".join(
            self.vocab[language][token]
            for token in path.tolist()
            if token != self.config.BLANK_ID
        ).replace("▁", " ").strip()


def load_indic_model(model_dir):
    return IndicCTCModel(model_dir)


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
        tokens, confidences, runs = [], [], []
        for start, end in zip(starts.tolist(), ends.tolist()):
            local_id = path[start].item()
            if local_id == self.model.config.BLANK_ID:
                continue
            token = self.model.vocab[code][local_id]
            tokens.append(token)
            runs.append((token, start, end))
            global_id = global_indices[local_id]
            confidences.append(global_log_probs[start:end, global_id].exp().max().item())
        text = "".join(tokens).replace("▁", " ").strip()
        words, word = [], None
        for token, start, end in runs:
            starts_word = token.startswith("▁")
            piece = token.removeprefix("▁")
            if starts_word and word:
                words.append(word)
                word = None
            if not piece:
                continue
            if word is None:
                word = {
                    "text": piece,
                    "start_sec": start * INDIC_FRAME_SECONDS,
                    "end_sec": end * INDIC_FRAME_SECONDS,
                }
            else:
                word["text"] += piece
                word["end_sec"] = end * INDIC_FRAME_SECONDS
        if word:
            words.append(word)
        return {
            "language": language,
            "language_code": code,
            "text": text,
            "confidence": float(np.mean(confidences)) if confidences else 0.0,
            "token_count": len(tokens),
            "words": words,
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
    def __init__(self, model_dir, device, quantize=False):
        from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

        self.device = device
        self.processor = AutoProcessor.from_pretrained(model_dir, local_files_only=True)
        self.model = AutoModelForSpeechSeq2Seq.from_pretrained(model_dir, local_files_only=True).to(device).eval()
        if quantize:
            self.model = dynamic_int8(self.model, device)

    def _transcribe(self, audio_path, with_confidence):
        audio = load_audio(audio_path).numpy()
        inputs = self.processor(audio, sampling_rate=16000, return_tensors="pt", return_attention_mask=True)
        inputs = {key: value.to(self.device) for key, value in inputs.items()}
        with torch.inference_mode():
            output = self.model.generate(**inputs, language="en", task="transcribe", **(
                {"output_scores": True, "return_dict_in_generate": True} if with_confidence else {}
            ))
        sequences = output.sequences if with_confidence else output
        text = self.processor.batch_decode(sequences, skip_special_tokens=True)[0].strip()
        return {"text": text, "confidence": whisper_confidence(output)} if with_confidence else text

    def transcribe(self, audio_path):
        return self._transcribe(audio_path, False)

    def transcribe_with_confidence(self, audio_path):
        return self._transcribe(audio_path, True)

    def english_probability(self, audio_path):
        """Return Whisper's English language-token probability without decoding text."""
        started = time.perf_counter()
        if not hasattr(self, "_language_token_ids"):
            pairs = []
            for token in self.processor.tokenizer.all_special_tokens:
                if match := re.fullmatch(r"<\|([a-z]{2,3})\|>", token):
                    pairs.append((match.group(1), self.processor.tokenizer.convert_tokens_to_ids(token)))
            self._language_codes, ids = zip(*pairs)
            self._language_token_ids = torch.tensor(ids, device=self.device)
        audio = load_audio(audio_path).numpy()
        inputs = self.processor(audio, sampling_rate=16000, return_tensors="pt", return_attention_mask=True)
        inputs = {key: value.to(self.device) for key, value in inputs.items()}
        decoder = torch.tensor([[self.model.config.decoder_start_token_id]], device=self.device)
        with torch.inference_mode():
            logits = self.model(**inputs, decoder_input_ids=decoder).logits[0, -1, self._language_token_ids]
            probability = logits.softmax(-1)[self._language_codes.index("en")].item()
        return probability, (time.perf_counter() - started) * 1000


def whisper_confidence(generate_output):
    """Mean per-token probability of the tokens Whisper actually emitted."""
    if not generate_output.scores:
        return 0.0
    step_log_probs = torch.stack(generate_output.scores, dim=1).log_softmax(-1)
    generated_tokens = generate_output.sequences[:, -step_log_probs.shape[1]:]
    token_log_probs = step_log_probs.gather(-1, generated_tokens.unsqueeze(-1)).squeeze(-1)
    return token_log_probs.exp().mean().item()


class SpeechPipeline:
    def __init__(
        self, model_root, device=None, indic_dir=None, quantize_lid=False,
        quantize_whisper=False, lid_confidence_threshold=None, lid_head=None,
        whisper_lid_threshold=None, lid_candidate_threshold=0.3,
    ):
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.lid_confidence_threshold = lid_confidence_threshold
        self.whisper_lid_threshold = whisper_lid_threshold
        self.lid_candidate_threshold = lid_candidate_threshold
        if whisper_lid_threshold is not None and lid_head is None:
            raise ValueError("whisper_lid_threshold requires a trained binary lid_head")
        if whisper_lid_threshold is not None and lid_confidence_threshold is not None:
            raise ValueError("Use either whisper_lid_threshold or the legacy lid_confidence_threshold")
        if whisper_lid_threshold is not None and not 0 <= whisper_lid_threshold <= 1:
            raise ValueError("whisper_lid_threshold must be between 0 and 1")
        if not 0 <= lid_candidate_threshold <= 1:
            raise ValueError("lid_candidate_threshold must be between 0 and 1")
        root = Path(model_root)
        self.lid = VoxLinguaCoarseLID(
            root / "lid", self.device, quantize=quantize_lid, binary_head=lid_head,
        )
        self.indic = IndicASR(indic_dir or root / "indic")
        self.whisper = WhisperASR(root / "whisper", self.device, quantize=quantize_whisper)

    def _english_result(self, whisper_result, decision):
        return {
            "language": "english", "text": whisper_result["text"],
            "model": WHISPER_ID, "indic_total_ms": 0.0, "candidates": [],
            "asr_confidence": whisper_result["confidence"], "decision": decision,
        }

    def _indic_result(self, indic_result, decision):
        return {**indic_result, "asr_confidence": indic_result["confidence"], "decision": decision}

    def transcribe(self, audio_path):
        lid = self.lid.predict(audio_path)
        routing = {
            "legacy_language": lid["legacy_language"],
            "lid_confidence": lid["confidence"],
            "lid_scores": lid["scores"],
            "lid_latency_ms": lid["latency_ms"],
        }

        if getattr(self, "whisper_lid_threshold", None) is not None:
            english_score = lid["scores"]["english"]
            if english_score < self.lid_candidate_threshold:
                decision = f"binary-lid-indic({english_score:.2f})"
                return {**self._indic_result(self.indic.transcribe_all(audio_path), decision), **routing}
            whisper_score, whisper_lid_ms = self.whisper.english_probability(audio_path)
            routing.update({"whisper_lid_score": whisper_score, "whisper_lid_ms": whisper_lid_ms})
            if whisper_score >= self.whisper_lid_threshold:
                result = {"text": self.whisper.transcribe(audio_path), "confidence": None}
                decision = f"binary+whisper-english({english_score:.2f},{whisper_score:.2f})"
                return {**self._english_result(result, decision), **routing}
            decision = f"binary+whisper-indic({english_score:.2f},{whisper_score:.2f})"
            return {**self._indic_result(self.indic.transcribe_all(audio_path), decision), **routing}

        # A single short/noisy-audio LID call is not reliable enough to be the sole
        # router (VoxLingua107 confidence on ~2s real mic clips can collapse to
        # near-chance). Trust it only when it's actually confident; otherwise run
        # both engines and let the ASR that is more confident about its own output win.
        if self.lid_confidence_threshold is None or lid["confidence"] >= self.lid_confidence_threshold:
            decision = f"lid-confident({lid['confidence']:.2f})"
            if lid["coarse_group"] == "english":
                whisper_result = {"text": self.whisper.transcribe(audio_path), "confidence": None}
                return {**self._english_result(whisper_result, decision), **routing}
            return {**self._indic_result(self.indic.transcribe_all(audio_path), decision), **routing}

        # ponytail: ASR confidence scales are uncalibrated; keep this opt-in until
        # a representative routing set supports a calibrated comparison.
        whisper_result = self.whisper.transcribe_with_confidence(audio_path)
        indic_result = self.indic.transcribe_all(audio_path)
        decision = f"lid-ambiguous({lid['confidence']:.2f})"
        if whisper_result["confidence"] >= indic_result["confidence"]:
            return {**self._english_result(whisper_result, f"{decision}-whisper-won"), **routing}
        return {**self._indic_result(indic_result, f"{decision}-indic-won"), **routing}


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
    parser.add_argument("--quantize-lid", action="store_true")
    parser.add_argument("--quantize-whisper", action="store_true")
    parser.add_argument("--lid-threshold", type=float)
    parser.add_argument("--lid-head", help="trained Indian-English-vs-Indic ECAPA head")
    parser.add_argument("--whisper-lid-threshold", type=float, help="verify binary LID candidates with Whisper")
    parser.add_argument("--lid-candidate-threshold", type=float, default=0.3)


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
    pipeline = SpeechPipeline(
        args.model_root,
        args.device,
        args.indic_dir,
        quantize_lid=args.quantize_lid,
        quantize_whisper=args.quantize_whisper,
        lid_confidence_threshold=args.lid_threshold,
        lid_head=args.lid_head,
        whisper_lid_threshold=args.whisper_lid_threshold,
        lid_candidate_threshold=args.lid_candidate_threshold,
    )
    if args.command == "transcribe":
        print(json.dumps(pipeline.transcribe(args.audio), ensure_ascii=False, indent=2))
    else:
        evaluate(args.manifest, pipeline, args.output)


if __name__ == "__main__":
    main()
