"""Export Whisper's exact 30-second log-mel frontend as standard ONNX."""

import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sih_speech import load_audio  # noqa: E402


class WhisperMelFrontend(nn.Module):
    def __init__(self, mel_filters):
        super().__init__()
        window = torch.hann_window(400)
        bins = torch.arange(201).unsqueeze(1)
        samples = torch.arange(400).unsqueeze(0)
        phase = 2 * torch.pi * bins * samples / 400
        self.register_buffer("real_kernel", (torch.cos(phase) * window).unsqueeze(1))
        self.register_buffer("imag_kernel", (-torch.sin(phase) * window).unsqueeze(1))
        self.register_buffer("mel_filters", mel_filters)

    def forward(self, waveform):
        waveform = torch.nn.functional.pad(waveform.unsqueeze(1), (200, 200), mode="reflect")
        real = torch.nn.functional.conv1d(waveform, self.real_kernel, stride=160)[..., :-1]
        imag = torch.nn.functional.conv1d(waveform, self.imag_kernel, stride=160)[..., :-1]
        power = real.square() + imag.square()
        mel = torch.matmul(power.transpose(1, 2), self.mel_filters).transpose(1, 2)
        log_spec = torch.log(mel.clamp_min(1e-10)) / np.log(10.0)
        log_spec = torch.maximum(log_spec, log_spec.amax(dim=(1, 2), keepdim=True) - 8.0)
        features = (log_spec + 4.0) / 4.0
        # sherpa-onnx pads feature frames, not raw waveform samples. Keeping 50
        # zero frames at the end prevents Whisper from missing EOT on long audio.
        return torch.nn.functional.pad(
            torch.nn.functional.pad(features, (0, 1450))[..., :2950], (0, 50)
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--whisper-dir", type=Path, default=Path("deployment_models/common/whisper"))
    parser.add_argument("--output", type=Path, default=Path("deployment_models/android/whisper/preprocessor.onnx"))
    parser.add_argument("--audio", type=Path, default=Path("artifacts/recordings/last_mic.wav"))
    args = parser.parse_args()

    from transformers import AutoProcessor

    processor = AutoProcessor.from_pretrained(args.whisper_dir, local_files_only=True)
    model = WhisperMelFrontend(torch.from_numpy(processor.feature_extractor.mel_filters).float()).eval()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    example = torch.zeros(1, 32_000)
    torch.onnx.export(
        model, example, str(args.output), input_names=["waveform"], output_names=["mel"],
        dynamic_axes={"waveform": {0: "batch", 1: "samples"}, "mel": {0: "batch", 2: "frames"}},
        opset_version=17, dynamo=False,
    )
    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    waveforms = [torch.randn(71_111)]
    if args.audio.is_file():
        waveforms.append(load_audio(args.audio))
    for waveform in waveforms:
        signal = waveform[:480_000].unsqueeze(0)
        with torch.inference_mode():
            expected = model(signal).numpy()
        actual = session.run(None, {"waveform": signal.numpy()})[0]
        difference = np.abs(actual - expected)
        report = {"samples": waveform.numel(), "max_abs_error": float(difference.max()),
                  "mean_abs_error": float(difference.mean())}
        print(report)
        if report["max_abs_error"] > 1e-3:
            raise RuntimeError("ONNX frontend does not match the Transformers frontend")
    print(f"Verified ONNX frontend: {args.output.resolve()} ({args.output.stat().st_size / 2**20:.2f} MiB)")


if __name__ == "__main__":
    main()
