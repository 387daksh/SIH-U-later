"""Export IndicConformer's TorchScript mel frontend as standard ONNX and verify parity."""

import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from torch import nn
from torch.nn import functional as F

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sih_speech import load_audio  # noqa: E402


def tensor_constants(module):
    values = []
    for node in module.graph.nodes():
        if node.kind() != "prim::Constant":
            continue
        value = node.output().toIValue()
        if isinstance(value, torch.Tensor):
            values.append(value)
    return values


class IndicMelFrontend(nn.Module):
    def __init__(self, window, mel_filters):
        super().__init__()
        padded_window = F.pad(window, (56, 56))
        bins = torch.arange(257).unsqueeze(1)
        samples = torch.arange(512).unsqueeze(0)
        phase = 2 * torch.pi * bins * samples / 512
        self.register_buffer("real_kernel", (torch.cos(phase) * padded_window).unsqueeze(1))
        self.register_buffer("imag_kernel", (-torch.sin(phase) * padded_window).unsqueeze(1))
        self.register_buffer("mel_filters", mel_filters)

    def forward(self, input_signal, length):
        lengths = torch.div(length, 160, rounding_mode="floor") + 1
        previous = F.pad(input_signal, (1, 0))[:, :-1]
        waveform = input_signal - 0.97 * previous
        waveform = F.pad(waveform.unsqueeze(1), (256, 256), mode="reflect")
        real = F.conv1d(waveform, self.real_kernel, stride=160)
        imag = F.conv1d(waveform, self.imag_kernel, stride=160)
        power = real.square() + imag.square()
        features = torch.matmul(power.transpose(1, 2), self.mel_filters).transpose(1, 2)
        features = torch.log(features + 5.960464477539063e-8)
        frames = torch.arange(features.shape[-1], device=features.device).unsqueeze(0)
        valid = frames < lengths.unsqueeze(1)
        masked = features.masked_fill(~valid.unsqueeze(1), 0.0)
        means = masked.sum(2, keepdim=True) / lengths.view(-1, 1, 1)
        centered = masked - means
        variance = centered.masked_fill(~valid.unsqueeze(1), 0.0).square().sum(2, keepdim=True)
        variance = variance / (lengths.view(-1, 1, 1) - 1)
        std = variance.clamp_min(5.960464477539063e-8).sqrt()
        normalized = centered / (std + 1e-5)
        return normalized.masked_fill(~valid.unsqueeze(1), 0.0), lengths


def verify(torchscript, session, waveform):
    signal = waveform.unsqueeze(0)
    length = torch.tensor([waveform.numel()], dtype=torch.int64)
    with torch.inference_mode():
        expected, expected_length = torchscript(signal, length)
    actual, actual_length = session.run(
        None, {"input_signal": signal.numpy(), "length": length.numpy()},
    )
    difference = np.abs(actual - expected.numpy())
    return {
        "samples": waveform.numel(),
        "frames": int(actual_length[0]),
        "max_abs_error": float(difference.max()),
        "mean_abs_error": float(difference.mean()),
        "length_match": bool(np.array_equal(actual_length, expected_length.numpy())),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--indic-dir", type=Path, default=Path("deployment_models/indic/hybrid_int8"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--audio", type=Path, default=Path("artifacts/recordings/last_mic.wav"))
    args = parser.parse_args()
    source = args.indic_dir / "assets" / "preprocessor.ts"
    output = args.output or args.indic_dir / "assets" / "preprocessor.onnx"
    output.parent.mkdir(parents=True, exist_ok=True)
    torchscript = torch.jit.load(str(source), map_location="cpu").eval()
    constants = tensor_constants(torchscript)
    window = next(value for value in constants if value.shape == (400,)).float()
    mel_filters = next(value for value in constants if value.shape == (257, 80)).float()
    model = IndicMelFrontend(window, mel_filters).eval()
    example = torch.zeros(1, 32_000)
    torch.onnx.export(
        model, (example, torch.tensor([32_000], dtype=torch.int64)), str(output),
        input_names=["input_signal", "length"],
        output_names=["audio_signal", "processed_length"],
        dynamic_axes={"input_signal": {0: "batch", 1: "samples"}, "length": {0: "batch"},
                      "audio_signal": {0: "batch", 2: "frames"}, "processed_length": {0: "batch"}},
        opset_version=17, dynamo=False,
    )
    session = ort.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    waveforms = [torch.randn(16_000), torch.randn(47_123)]
    if args.audio.is_file():
        waveforms.append(load_audio(args.audio))
    reports = [verify(torchscript, session, waveform) for waveform in waveforms]
    for report in reports:
        print(report)
    if any(not report["length_match"] or report["max_abs_error"] > 5e-4 for report in reports):
        raise RuntimeError("ONNX frontend does not match the supplied TorchScript frontend")
    print(f"Verified ONNX frontend: {output.resolve()} ({output.stat().st_size / 2**20:.2f} MiB)")


if __name__ == "__main__":
    main()
