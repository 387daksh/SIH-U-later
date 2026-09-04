"""Build the minimal offline model tree loaded by sih_speech.pipeline."""

import argparse
import json
import shutil
from pathlib import Path

import onnx
from onnx.external_data_helper import _get_all_tensors


ROOT = Path(__file__).resolve().parents[1]
WHISPER_FILES = {
    "added_tokens.json", "config.json", "generation_config.json", "merges.txt",
    "model.safetensors", "normalizer.json", "preprocessor_config.json",
    "special_tokens_map.json", "tokenizer.json", "tokenizer_config.json", "vocab.json",
}
LID_FILES = {"embedding_model.ckpt", "classifier.ckpt", "label_encoder.txt"}
INDIC_FILES = {"preprocessor.ts", "encoder.onnx", "ctc_decoder.onnx", "vocab.json", "language_masks.json"}


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def external_files(graph):
    model = onnx.load(graph, load_external_data=False)
    return {
        item.value
        for tensor in _get_all_tensors(model)
        for item in tensor.external_data
        if item.key == "location"
    }


def build(args):
    output = args.output.resolve()
    for name in LID_FILES:
        copy(args.model_root / "lid" / name, output / "common" / "lid" / name)
    for name in WHISPER_FILES:
        copy(args.model_root / "whisper" / name, output / "common" / "whisper" / name)

    variants = {"fp32": args.fp32, "hybrid_int8": args.hybrid, "aggressive_int8": args.aggressive}
    for variant, source in variants.items():
        target = output / "indic" / variant
        copy(source / "config.json", target / "config.json")
        dependencies = set(INDIC_FILES)
        for graph in ("encoder.onnx", "ctc_decoder.onnx"):
            dependencies.update(external_files(source / "assets" / graph))
        for name in dependencies:
            copy(source / "assets" / name, target / "assets" / name)

    manifest = {}
    for directory in [output / "common", *(output / "indic" / name for name in variants)]:
        files = [path for path in directory.rglob("*") if path.is_file()]
        manifest[str(directory.relative_to(output))] = {
            "files": len(files),
            "mib": sum(path.stat().st_size for path in files) / 2**20,
        }
    (output / "bundle_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path, default=ROOT / "models")
    parser.add_argument("--fp32", type=Path, required=True)
    parser.add_argument("--hybrid", type=Path, default=ROOT / "indic-conformer-600m-int8-onnx-patched")
    parser.add_argument("--aggressive", type=Path, default=ROOT / "quantized" / "indic-int8-patched")
    parser.add_argument("--output", type=Path, default=ROOT / "deployment_models")
    build(parser.parse_args())
