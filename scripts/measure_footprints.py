"""Measure model parameters, serialized sizes, and peak process RAM."""

import argparse
import gc
import json
import shutil
import threading
import time
from math import prod
from pathlib import Path

import onnx
import psutil
import torch
from torch import nn

from sih_speech import FrozenECAPA, WhisperASR, load_audio, load_indic_model, load_vox_classifier


class VoxLinguaModel(nn.Module):
    def __init__(self, model_dir):
        super().__init__()
        self.extractor = FrozenECAPA(model_dir, "cpu")
        self.classifier = load_vox_classifier(model_dir, "cpu")

    def forward(self, waveform):
        embedding = self.extractor(waveform, torch.ones(waveform.shape[0]))
        return self.classifier(embedding.unsqueeze(1))


def mib(path):
    path = Path(path)
    files = [path] if path.is_file() else [item for item in path.rglob("*") if item.is_file()]
    return sum(item.stat().st_size for item in files) / 2**20


def save_state(state, path, dtype=None):
    converted = {
        key: value.to(dtype) if dtype and torch.is_tensor(value) and value.is_floating_point() else value
        for key, value in state.items()
    }
    torch.save(converted, path)
    return mib(path)


def quantized_state_size(model, path):
    quantized = torch.ao.quantization.quantize_dynamic(model.cpu(), {nn.Linear}, dtype=torch.qint8)
    torch.save(quantized.state_dict(), path)
    size = mib(path)
    del quantized
    return size


def torch_sizes(model, output_dir, name):
    output_dir.mkdir(parents=True, exist_ok=True)
    state = model.state_dict()
    return {
        "fp32_mib": save_state(state, output_dir / f"{name}-fp32.pt"),
        "fp16_mib": save_state(state, output_dir / f"{name}-fp16.pt", torch.float16),
        "int8_mib": quantized_state_size(model, output_dir / f"{name}-dynamic-int8.pt"),
    }


def whisper_sizes(model, output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)
    fp32_dir, fp16_dir = output_dir / "whisper-fp32", output_dir / "whisper-fp16"
    model.save_pretrained(fp32_dir, safe_serialization=True)
    model.half().save_pretrained(fp16_dir, safe_serialization=True)
    model.float()
    return {
        "fp32_mib": mib(fp32_dir),
        "fp16_mib": mib(fp16_dir),
        "int8_mib": quantized_state_size(model, output_dir / "whisper-dynamic-int8.pt"),
    }


def onnx_parameter_count(model_dir):
    total = 0
    for path in (Path(model_dir) / "assets").glob("*.onnx"):
        graph = onnx.load(str(path), load_external_data=False).graph
        total += sum(prod(initializer.dims) for initializer in graph.initializer)
    return total


def copy_indic_support(source, destination):
    destination.mkdir(parents=True, exist_ok=True)
    for path in source.iterdir():
        if path.suffix != ".onnx" and not path.name.startswith(("onnx__", "layers.", "Constant")):
            if path.is_file():
                shutil.copy2(path, destination / path.name)


def convert_indic(source_dir, output_dir):
    from onnxconverter_common import float16
    from onnxruntime.quantization import QuantType, quantize_dynamic

    source = Path(source_dir) / "assets"
    fp16_dir = Path(output_dir) / "indic-fp16" / "assets"
    int8_dir = Path(output_dir) / "indic-int8" / "assets"
    for destination in (fp16_dir.parent, int8_dir.parent):
        destination.mkdir(parents=True, exist_ok=True)
        for name in ("config.json", "model_onnx.py"):
            shutil.copy2(Path(source_dir) / name, destination / name)
    copy_indic_support(source, fp16_dir)
    copy_indic_support(source, int8_dir)
    for path in source.glob("*.onnx"):
        if not (fp16_dir / path.name).exists():
            print(f"FP16: {path.name}", flush=True)
            model = float16.convert_float_to_float16(
                onnx.load(str(path)), keep_io_types=True, disable_shape_infer=True
            )
            onnx.save_model(
                model,
                str(fp16_dir / path.name),
                save_as_external_data=True,
                all_tensors_to_one_file=True,
                location=f"{path.name}.data",
                size_threshold=1024,
            )
            del model
            gc.collect()
        if not (int8_dir / path.name).exists():
            print(f"INT8: {path.name}", flush=True)
            model = onnx.load(str(path))
            quantize_dynamic(
                model,
                str(int8_dir / path.name),
                weight_type=QuantType.QInt8,
                per_channel=True,
                use_external_data_format=True,
            )
            del model
            gc.collect()
    return mib(Path(output_dir) / "indic-fp16"), mib(Path(output_dir) / "indic-int8")


def peak_rss(call):
    process = psutil.Process()
    start = process.memory_info().rss
    peak = start
    running = True

    def sample():
        nonlocal peak
        while running:
            peak = max(peak, process.memory_info().rss)
            time.sleep(0.01)

    thread = threading.Thread(target=sample, daemon=True)
    thread.start()
    call()
    running = False
    thread.join()
    return {"peak_rss_mib": peak / 2**20, "incremental_rss_mib": (peak - start) / 2**20}


def measure_peak(kind, model_root, indic_dir, audio):
    if kind == "lid":
        def run():
            model = VoxLinguaModel(Path(model_root) / "lid")
            waveform = load_audio(audio).unsqueeze(0)
            with torch.inference_mode():
                model(waveform)
    elif kind == "whisper":
        def run():
            WhisperASR(Path(model_root) / "whisper", "cpu").transcribe(audio)
    else:
        def run():
            model = load_indic_model(indic_dir)
            with torch.inference_mode():
                model(load_audio(audio).unsqueeze(0), "hi", "ctc")
    print(json.dumps({"model": kind, **peak_rss(run)}))


def measure_sizes(model_root, indic_dir, output_dir, convert):
    root, output = Path(model_root), Path(output_dir)
    lid = VoxLinguaModel(root / "lid")
    lid_result = {
        "parameters": sum(parameter.numel() for parameter in lid.parameters()),
        **torch_sizes(lid, output, "lid"),
        "quantizer": "PyTorch dynamic INT8 (Linear layers only)",
        "int4": None,
    }
    del lid
    gc.collect()

    from transformers import AutoModelForSpeechSeq2Seq
    whisper = AutoModelForSpeechSeq2Seq.from_pretrained(root / "whisper", local_files_only=True).eval()
    whisper_result = {
        "parameters": sum(parameter.numel() for parameter in whisper.parameters()),
        **whisper_sizes(whisper, output),
        "quantizer": "PyTorch dynamic INT8 (Linear layers)",
        "int4": None,
    }
    del whisper
    gc.collect()

    indic_result = {
        "parameters": onnx_parameter_count(indic_dir),
        "fp32_mib": mib(indic_dir),
        "fp16_mib": None,
        "int8_mib": None,
        "quantizer": "ONNX float16 converter + ONNX Runtime dynamic INT8",
        "int4": None,
    }
    if convert:
        indic_result["fp16_mib"], indic_result["int8_mib"] = convert_indic(indic_dir, output)
    report = {"lid": lid_result, "indic": indic_result, "whisper": whisper_result}
    output.mkdir(parents=True, exist_ok=True)
    (output / "footprints.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    sizes = subparsers.add_parser("sizes")
    sizes.add_argument("--model-root", default="models")
    sizes.add_argument("--indic-dir", required=True)
    sizes.add_argument("--output-dir", default="quantized")
    sizes.add_argument("--convert-indic", action="store_true")
    peak = subparsers.add_parser("peak")
    peak.add_argument("kind", choices=["lid", "indic", "whisper"])
    peak.add_argument("--audio", required=True)
    peak.add_argument("--model-root", default="models")
    peak.add_argument("--indic-dir", required=True)
    args = parser.parse_args()
    if args.command == "sizes":
        measure_sizes(args.model_root, args.indic_dir, args.output_dir, args.convert_indic)
    else:
        measure_peak(args.kind, args.model_root, args.indic_dir, args.audio)


if __name__ == "__main__":
    main()
