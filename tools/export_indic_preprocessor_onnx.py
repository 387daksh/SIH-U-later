"""Export IndicConformer's TorchScript mel-spectrogram preprocessor to ONNX.

`assets/preprocessor.ts` (NeMo's AudioToMelSpectrogramPreprocessor) is the one
part of the Indic pipeline that isn't ONNX -- it's loaded via torch.jit.load,
which means running it on Android would require bundling LibTorch Mobile
alongside ONNX Runtime just for this one small module. It's built entirely
from standard ops (STFT, mel filterbank matmul, per-utterance CMVN), so it
can be reimplemented and exported to ONNX directly.

torch.stft(..., return_complex=True) is not traceable by the legacy ONNX
exporter, so the STFT is replaced with two real-valued Conv1d ops (cosine and
sine DFT bases as fixed kernels, stride=hop) -- mathematically equivalent,
exports cleanly, and handles dynamic-length audio natively. Validated to
match the original TorchScript module to float32 noise (~1e-3 max abs diff)
on real audio of varying length.
"""

import argparse
import math
from pathlib import Path

import torch
from torch import nn


class OnnxFriendlyIndicPreprocessor(nn.Module):
    def __init__(self, hop, preemph, window, mel_fb, log_eps, n_fft=512):
        super().__init__()
        self.hop = hop
        self.preemph = preemph
        self.log_eps = log_eps
        self.n_fft = n_fft
        n_freq = n_fft // 2 + 1
        win_length = window.shape[0]
        pad = (n_fft - win_length) // 2
        window_padded = torch.zeros(n_fft)
        window_padded[pad:pad + win_length] = window
        n = torch.arange(n_fft).float()
        k = torch.arange(n_freq).float()
        angle = 2 * math.pi * torch.outer(k, n) / n_fft
        self.register_buffer("cos_kernel", (torch.cos(angle) * window_padded.unsqueeze(0)).unsqueeze(1))
        self.register_buffer("sin_kernel", (-torch.sin(angle) * window_padded.unsqueeze(0)).unsqueeze(1))
        self.register_buffer("mel_fb", mel_fb)

    def forward(self, input_signal: torch.Tensor, length: torch.Tensor):
        lengths = torch.div(length, self.hop, rounding_mode="floor") + 1
        padded = torch.nn.functional.pad(input_signal, [1, 0])
        waveform = input_signal - self.preemph * padded[:, :-1]
        x = torch.nn.functional.pad(waveform.unsqueeze(1), [self.n_fft // 2, self.n_fft // 2], mode="reflect")
        real = torch.nn.functional.conv1d(x, self.cos_kernel, stride=self.hop)
        imag = torch.nn.functional.conv1d(x, self.sin_kernel, stride=self.hop)
        power = real * real + imag * imag
        mel = torch.matmul(power.transpose(-1, -2), self.mel_fb)
        features = mel.transpose(-1, -2)
        features = torch.log(features + self.log_eps)

        idx = torch.arange(features.shape[-1], device=features.device)
        mask = (idx.unsqueeze(0) < lengths.unsqueeze(1)).unsqueeze(1)
        inv_mask = ~mask
        features = features.masked_fill(inv_mask, 0.0)
        means = features.sum(dim=2, keepdim=True) / lengths.view(-1, 1, 1)
        centered = features - means
        var = (centered.masked_fill(inv_mask, 0.0) ** 2).sum(dim=2, keepdim=True) / (lengths.view(-1, 1, 1) - 1)
        var = torch.clamp(var, min=5.9604644775390625e-08)
        stds = torch.sqrt(var)
        normalized = (centered / (stds + 1e-05)).masked_fill(inv_mask, 0.0)
        return normalized, lengths


def build_from_traced(traced_path):
    reference = torch.jit.load(str(traced_path), map_location="cpu").eval()
    _, constants = reference.forward.code_with_constants
    module = OnnxFriendlyIndicPreprocessor(
        hop=int(constants["c0"]),
        preemph=float(constants["c2"]),
        window=constants["c3"].float(),
        mel_fb=constants["c4"].float(),
        log_eps=float(constants["c5"]),
    ).eval()
    return reference, module


def validate(reference, module, waveform, length):
    with torch.no_grad():
        ref_features, ref_lengths = reference(waveform, length)
        my_features, my_lengths = module(waveform, length)
    assert ref_features.shape == my_features.shape
    assert torch.equal(ref_lengths, my_lengths)
    diff = (ref_features - my_features).abs()
    return diff.max().item(), diff.mean().item()


def export(module, waveform, length, output_path):
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        module, (waveform, length), str(output_path),
        input_names=["input_signal", "length"],
        output_names=["features", "lengths"],
        dynamic_axes={"input_signal": {1: "samples"}, "features": {2: "time"}},
        opset_version=17, dynamo=False,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--traced", default="models/indic/assets/preprocessor.ts")
    parser.add_argument("--sample-audio", required=True, help="any 16kHz mono wav, for tracing + validation")
    parser.add_argument("--output", default="models/indic/assets/preprocessor.onnx")
    args = parser.parse_args()

    from sih_speech.pipeline import load_audio

    reference, module = build_from_traced(args.traced)
    waveform = load_audio(args.sample_audio).unsqueeze(0)
    length = torch.tensor([waveform.shape[-1]])

    max_diff, mean_diff = validate(reference, module, waveform, length)
    print(f"parity vs traced module: max abs diff={max_diff:.6f} mean abs diff={mean_diff:.8f}")
    if max_diff > 0.01:
        raise SystemExit("Parity check failed -- diff too large to trust this export.")

    export(module, waveform, length, args.output)
    print(f"exported to {args.output}")


if __name__ == "__main__":
    main()
