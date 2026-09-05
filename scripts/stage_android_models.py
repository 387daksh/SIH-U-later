"""Build the untracked model folder consumed by the Android demo app."""

import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

import onnxruntime as ort
import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]


def copy(source, target):
    if not source.is_file():
        raise FileNotFoundError(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def check_english(bundle, audio_path):
    if not audio_path.is_file():
        print(f"English smoke test skipped; missing {audio_path}")
        return
    whisper = bundle / "whisper"
    config = json.loads((whisper / "config.json").read_text(encoding="utf-8"))
    audio, sample_rate = sf.read(audio_path, dtype="float32")
    if sample_rate != 16_000:
        raise ValueError(f"English smoke audio must be 16 kHz, got {sample_rate}")
    audio = np.asarray(audio[:480_000], dtype=np.float32)[None]
    preprocessor = ort.InferenceSession(str(whisper / "preprocessor.onnx"))
    encoder = ort.InferenceSession(str(whisper / "encoder.onnx"))
    decoder = ort.InferenceSession(str(whisper / "decoder.onnx"))
    mel = preprocessor.run(None, {"waveform": audio})[0]
    cross_k, cross_v = encoder.run(None, {"mel": mel})
    cache_shape = (config["n_text_layer"], 1, config["n_text_ctx"], config["n_text_state"])
    empty = np.zeros(cache_shape, dtype=np.float32)

    def step(token_ids, cache_k, cache_v, offset):
        return decoder.run(None, {
            "tokens": np.array([token_ids], dtype=np.int64),
            "in_n_layer_self_k_cache": cache_k, "in_n_layer_self_v_cache": cache_v,
            "n_layer_cross_k": cross_k, "n_layer_cross_v": cross_v,
            "offset": np.array([offset], dtype=np.int64),
        })

    logits, _, _ = step([config["sot"]], empty, empty, 0)
    languages = list(config["language_tokens"].items())
    scores = np.array([logits[0, -1, token] for _, token in languages])
    probabilities = np.exp(scores - scores.max()); probabilities /= probabilities.sum()
    english_score = float(probabilities[[code for code, _ in languages].index("en")])
    if english_score < config["english_threshold"]:
        raise RuntimeError(f"Android bundle misrouted real English audio ({english_score:.1%})")

    prompt = [config["sot"], config["language_tokens"]["en"], config["transcribe"],
              config["no_timestamps"]]
    logits, cache_k, cache_v = step(prompt, empty, empty, 0)
    generated, ended = [], False
    for offset in range(len(prompt), config["n_text_ctx"]):
        scores = logits[0, -1].copy()
        for token in (config["no_timestamps"], config["sot"], config["no_speech"], config["translate"]):
            scores[token] = -np.inf
        if not generated:
            scores[config["blank_id"]] = scores[config["eot"]] = -np.inf
        token = int(scores.argmax())
        if token == config["eot"]:
            ended = True; break
        generated.append(token)
        logits, cache_k, cache_v = step([token], cache_k, cache_v, offset)
    if not ended:
        raise RuntimeError("Whisper did not emit EOT; decoder/model pairing is unsuitable")
    token_bytes = {int(line.split()[1]): (b"" if line.split()[0] == "=" else
                   base64.b64decode(line.split()[0], validate=True))
                   for line in (whisper / "tokens.txt").read_text().splitlines()}
    text = b"".join(token_bytes[token] for token in generated if token in token_bytes).decode().strip()
    print(f"Android English smoke: score={english_score:.1%}, text={text}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/android_models")
    indic_default = ROOT / "deployment_models/indic/hybrid_int8"
    if not (indic_default / "assets" / "preprocessor.ts").exists():
        indic_default = ROOT / "models/indic"
    parser.add_argument("--indic-dir", type=Path, default=indic_default)
    parser.add_argument("--whisper-dir", type=Path, default=ROOT / "deployment_models/android/whisper")
    parser.add_argument("--vad-path", type=Path, default=ROOT / "models/vad/silero_vad.onnx")
    args = parser.parse_args()

    if not args.vad_path.is_file():
        subprocess.run([sys.executable, str(ROOT / "scripts/download_silero_vad.py"),
                        "--output", str(args.vad_path)], check=True, cwd=ROOT)

    subprocess.run([sys.executable, str(ROOT / "scripts/export_indic_preprocessor_onnx.py"),
                    "--indic-dir", str(args.indic_dir)], check=True, cwd=ROOT)
    subprocess.run([sys.executable, str(ROOT / "scripts/export_whisper_preprocessor_onnx.py"),
                    "--whisper-dir", str(ROOT / "models/whisper"),
                    "--output", str(args.whisper_dir / "preprocessor.onnx")], check=True, cwd=ROOT)

    files = {
        args.indic_dir / "config.json": args.output / "indic/config.json",
        args.indic_dir / "assets/preprocessor.onnx": args.output / "indic/preprocessor.onnx",
        args.indic_dir / "assets/encoder.onnx": args.output / "indic/encoder.onnx",
        args.indic_dir / "assets/ctc_decoder.onnx": args.output / "indic/ctc_decoder.onnx",
        args.indic_dir / "assets/vocab.json": args.output / "indic/vocab.json",
        args.indic_dir / "assets/language_masks.json": args.output / "indic/language_masks.json",
        args.whisper_dir / "preprocessor.onnx": args.output / "whisper/preprocessor.onnx",
        args.whisper_dir / "tiny-encoder.onnx": args.output / "whisper/encoder.onnx",
        args.whisper_dir / "tiny-decoder.int8.onnx": args.output / "whisper/decoder.onnx",
        args.whisper_dir / "tiny-tokens.txt": args.output / "whisper/tokens.txt",
        args.vad_path: args.output / "vad/silero_vad.onnx",
    }
    for source, target in files.items():
        copy(source, target)

    session = ort.InferenceSession(str(args.whisper_dir / "tiny-encoder.onnx"),
                                   providers=["CPUExecutionProvider"])
    metadata = session.get_modelmeta().custom_metadata_map
    codes = metadata["all_language_codes"].split(",")
    ids = [int(value) for value in metadata["all_language_tokens"].split(",")]
    config = {
        "english_threshold": 0.30,
        "sot": int(metadata["sot"]), "eot": int(metadata["eot"]),
        "transcribe": int(metadata["transcribe"]),
        "no_timestamps": int(metadata["no_timestamps"]),
        "no_speech": int(metadata["no_speech"]), "translate": int(metadata["translate"]),
        "blank_id": int(metadata["blank_id"]),
        "non_speech_tokens": [int(value) for value in metadata["non_speech_tokens"].split(",")],
        "language_tokens": dict(zip(codes, ids)),
        "n_vocab": int(metadata["n_vocab"]), "n_text_ctx": int(metadata["n_text_ctx"]),
        "n_text_layer": int(metadata["n_text_layer"]), "n_text_state": int(metadata["n_text_state"]),
    }
    (args.output / "whisper/config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
    staged = sorted(path for path in args.output.rglob("*")
                    if path.is_file() and path.name != "manifest.json")
    manifest = {
        "profile": "android-hybrid-int8",
        "bytes": sum(path.stat().st_size for path in staged),
        "files": {str(path.relative_to(args.output)).replace("\\", "/"): {
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        } for path in staged},
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Android model bundle: {args.output.resolve()}")
    print(f"Total: {manifest['bytes'] / 2**20:.2f} MiB ({len(staged)} files)")
    check_english(args.output, ROOT / "artifacts/recordings/english_mic.wav")


if __name__ == "__main__":
    main()
