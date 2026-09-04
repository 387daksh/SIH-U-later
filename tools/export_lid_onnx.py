"""Export the VoxLingua coarse LID (ECAPA-TDNN + restricted-9-way classifier)
to a single ONNX graph, so it needs no separate feature-extraction code on
Android -- raw waveform samples in, 9 softmax scores out.

Two separate ONNX-export incompatibilities in SpeechBrain's modules, both
fixed by substituting an equivalent module before tracing:

1. Fbank's STFT (speechbrain.processing.features.STFT) calls
   torch.stft(..., return_complex=True), which the legacy ONNX exporter
   cannot trace -- same class of problem as IndicConformer's preprocessor
   (see export_indic_preprocessor_onnx.py). Fixed with an equivalent Conv1d
   real-DFT.

2. InputNormalization's "sentence" branch (speechbrain.processing.features)
   uses a Python for-loop, `torch.empty_like` + in-place indexed assignment,
   and a `.int()`-cast tensor used as a slice bound -- none of which trace
   correctly. This one is *dangerous*: it doesn't crash or obviously misbehave,
   it silently exports a graph that's correct only for inputs matching the
   exact shape (and, worse, apparently also something in the exact *content*)
   used at trace time. A single-sample validation at export time (which this
   script originally did) does NOT catch it -- it was only found by scoring
   the exported graph's *coarse routing accuracy* against many real gold-labeled
   samples (scripts/calibrate_routing.py-style), which dropped to 96.7% with
   every single error an English clip misrouted to Hindi. Root-caused by
   diffing intermediate tensors (mel features matched to ~2e-4, but the
   normalized features diverged by ~44 on values in the roughly [-30,60]
   range) down to this one module. Fixed by replacing it with the
   mathematically equivalent vectorized form for our actual config
   (mean_norm=True, std_norm=False, batch size 1, no length masking needed
   since we don't pad): `x - x.mean(dim=1, keepdim=True)`.

Both replacements are genuine torch ops throughout (mel filterbank,
ECAPA-TDNN, classifier untouched) and validated to match the original
module to float32 noise (~1e-7) -- across MULTIPLE samples of different
lengths and content, not just one, specifically because of lesson #2 above.
"""

import argparse
import math
from pathlib import Path

import torch
from torch import nn

from sih_speech.pipeline import FrozenECAPA, load_vox_classifier, read_vox_indices

LABELS = ["english", "hindi", "gujarati", "marathi", "kannada", "malayalam", "tamil", "telugu", "bengali"]
CODES = ["en", "hi", "gu", "mr", "kn", "ml", "ta", "te", "bn"]


class OnnxFriendlySTFT(nn.Module):
    def __init__(self, n_fft, hop_length, window):
        super().__init__()
        self.n_fft = n_fft
        self.hop_length = hop_length
        n_freq = n_fft // 2 + 1
        n = torch.arange(n_fft).float()
        k = torch.arange(n_freq).float()
        angle = 2 * math.pi * torch.outer(k, n) / n_fft
        self.register_buffer("cos_kernel", (torch.cos(angle) * window.unsqueeze(0)).unsqueeze(1))
        self.register_buffer("sin_kernel", (-torch.sin(angle) * window.unsqueeze(0)).unsqueeze(1))

    def forward(self, x):
        x = torch.nn.functional.pad(x.unsqueeze(1), [self.n_fft // 2, self.n_fft // 2], mode="constant")
        real = torch.nn.functional.conv1d(x, self.cos_kernel, stride=self.hop_length)
        imag = torch.nn.functional.conv1d(x, self.sin_kernel, stride=self.hop_length)
        return torch.stack([real, imag], dim=-1).transpose(2, 1)  # [B, frames, freq, 2]


class OnnxFriendlySentenceMeanNorm(nn.Module):
    """Replaces InputNormalization(norm_type="sentence", std_norm=False) --
    see module docstring for why the original doesn't export correctly."""

    def forward(self, x, lengths):
        mean = x.mean(dim=1, keepdim=True)
        return x - mean


class LIDModel(nn.Module):
    """waveform [B, samples] -> 9-way softmax over english + 8 covered Indic labels."""

    def __init__(self, extractor, classifier, gather_indices):
        super().__init__()
        self.extractor = extractor
        self.classifier = classifier
        self.register_buffer("gather_indices", gather_indices)

    def forward(self, waveform):
        lengths = torch.ones(waveform.shape[0])
        embedding = self.extractor(waveform, lengths)
        log_probs = self.classifier(embedding.unsqueeze(1))[:, 0]
        restricted = log_probs.index_select(-1, self.gather_indices)
        return restricted.softmax(dim=-1)


def build(model_dir):
    extractor = FrozenECAPA(model_dir, "cpu")
    classifier = load_vox_classifier(model_dir, "cpu")
    indices = read_vox_indices(model_dir)
    gather_indices = torch.tensor([indices[c] for c in CODES], dtype=torch.long)

    orig_stft = extractor.features.compute_STFT
    extractor.features.compute_STFT = OnnxFriendlySTFT(
        orig_stft.n_fft, orig_stft.hop_length, orig_stft.window.clone(),
    )
    extractor.normalize = OnnxFriendlySentenceMeanNorm()
    return LIDModel(extractor, classifier, gather_indices).eval()


def reference_output(model_dir, waveform):
    extractor = FrozenECAPA(model_dir, "cpu")
    classifier = load_vox_classifier(model_dir, "cpu")
    indices = read_vox_indices(model_dir)
    gather_indices = torch.tensor([indices[c] for c in CODES], dtype=torch.long)
    with torch.no_grad():
        embedding = extractor(waveform, torch.ones(waveform.shape[0]))
        log_probs = classifier(embedding.unsqueeze(1))[:, 0]
        return log_probs.index_select(-1, gather_indices).softmax(dim=-1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", default="models/lid")
    parser.add_argument("--sample-audio", required=True, help="used to trace the graph")
    parser.add_argument(
        "--validate-audio", nargs="*", default=[],
        help="additional wav files (different lengths/content strongly recommended) to check "
             "the EXPORTED ONNX graph against -- a single traced sample is not sufficient, see "
             "module docstring for why",
    )
    parser.add_argument("--output", default="models/lid/lid.onnx")
    args = parser.parse_args()

    from sih_speech.pipeline import load_audio

    waveform = load_audio(args.sample_audio).unsqueeze(0)
    ref = reference_output(args.model_dir, waveform)
    model = build(args.model_dir)
    with torch.no_grad():
        patched = model(waveform)
    diff = (patched - ref).abs().max().item()
    print(f"parity vs original module (trace sample): max abs diff={diff:.8f}")
    if diff > 0.01:
        raise SystemExit("Parity check failed -- diff too large to trust this export.")

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model, (waveform,), str(output_path),
        input_names=["waveform"], output_names=["scores"],
        dynamic_axes={"waveform": {1: "samples"}},
        opset_version=17, dynamo=False,
    )
    print(f"exported to {output_path}")
    print(f"labels (output order): {LABELS}")

    if not args.validate_audio:
        print(
            "WARNING: no --validate-audio given. A single-sample parity check does NOT catch "
            "export bugs that only manifest on other input shapes/content (this happened once "
            "already -- see module docstring). Strongly recommend passing a few other files."
        )
        return

    import onnxruntime as ort

    session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
    worst = 0.0
    for audio_path in args.validate_audio:
        other_waveform = load_audio(audio_path).unsqueeze(0)
        other_ref = reference_output(args.model_dir, other_waveform)
        onnx_out = session.run(["scores"], {"waveform": other_waveform.numpy()})[0]
        other_diff = float((torch.from_numpy(onnx_out) - other_ref).abs().max())
        worst = max(worst, other_diff)
        status = "OK" if other_diff < 0.01 else "MISMATCH"
        print(f"  [{status}] {audio_path}: max abs diff={other_diff:.6f}")
    if worst > 0.01:
        raise SystemExit("Exported ONNX graph does not match the original module on one or more "
                          "validation files -- do not ship this export.")


if __name__ == "__main__":
    main()
