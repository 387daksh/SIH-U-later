"""Verify defaults and test whether +30 predictor units behave like +30 Hz."""

import argparse
import json
from pathlib import Path
import sys
import unicodedata

import numpy as np
import onnxruntime as ort

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


SENTENCES = {
    "en": ["help is coming", "send water now", "meet me at north gate"],
    "hi": ["मदद कीजिए", "पानी और दवा भेजिए", "मैं ठीक हूँ"],
}


class FastPitchProsody:
    """B=1 runtime wrapper that supplies neutral per-token controls by default."""

    def __init__(self, model, meta):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(
            str(model), options, providers=["CPUExecutionProvider"]
        )
        self.meta = json.loads(Path(meta).read_text(encoding="utf-8"))
        self.ids = {
            token: index
            for index, token in enumerate(self.meta["vocab"])
            if len(token) == 1
        }

    def tokens(self, text):
        normalized = unicodedata.normalize("NFC", text).lower()
        normalized = "".join(" " if char.isspace() else char for char in normalized)
        normalized = " ".join("".join(char for char in normalized if char in self.ids).split())
        return np.array([[self.ids[char] for char in normalized]], dtype=np.int64)

    def run(self, text, pitch_bias=None, duration_scale=None):
        tokens = self.tokens(text)
        if tokens.shape[1] == 0:
            raise ValueError("TTS text has no supported characters")
        token_shape = tokens.shape
        pitch_bias = np.zeros(token_shape, np.float32) if pitch_bias is None else pitch_bias
        duration_scale = (
            np.ones(token_shape, np.float32) if duration_scale is None else duration_scale
        )
        if pitch_bias.shape != token_shape or duration_scale.shape != token_shape:
            raise ValueError(
                f"Prosody arrays must match tokens {token_shape}; got "
                f"{pitch_bias.shape} and {duration_scale.shape}"
            )
        return self.session.run(
            None,
            {
                "tokens": tokens,
                "speaker_id": np.array([self.meta["speaker_id"]], np.int64),
                "length_scale": np.array(
                    [self.meta["length_scale_default"]], np.float32
                ),
                "pitch_bias": pitch_bias.astype(np.float32, copy=False),
                "duration_scale": duration_scale.astype(np.float32, copy=False),
            },
        )[0]


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def median_f0(waveform, sample_rate, hop_length):
    waveform = np.asarray(waveform).squeeze().astype(np.float64)
    frame_length = 2048
    minimum_lag = sample_rate // 500
    maximum_lag = sample_rate // 50
    estimates = []
    for start in range(0, max(0, len(waveform) - frame_length + 1), hop_length):
        frame = waveform[start : start + frame_length]
        frame = (frame - frame.mean()) * np.hanning(frame_length)
        if np.sqrt(np.mean(frame * frame)) < 0.005:
            continue
        correlation = np.correlate(frame, frame, mode="full")[frame_length - 1 :]
        lag = minimum_lag + int(np.argmax(correlation[minimum_lag : maximum_lag + 1]))
        estimates.append(sample_rate / lag)
    return float(np.median(estimates)) if estimates else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--original",
        type=Path,
        default=Path("models/tts/en-hi/fastpitch_en+hi.qdq-safe.onnx"),
    )
    parser.add_argument(
        "--prosody",
        type=Path,
        default=Path("models/tts/en-hi/fastpitch_en+hi.prosody.onnx"),
    )
    parser.add_argument(
        "--hifigan",
        type=Path,
        default=Path("models/tts/en-hi/hifigan_en+hi.minmax.onnx"),
    )
    parser.add_argument(
        "--meta", type=Path, default=Path("models/tts/en-hi/meta.json")
    )
    args = parser.parse_args()

    original = session(args.original)
    prosody = FastPitchProsody(args.prosody, args.meta)
    vocoder = session(args.hifigan)
    maximum = 0.0
    neutral_mels = {}
    print("neutral equivalence")
    for language, sentences in SENTENCES.items():
        for text in sentences:
            tokens = prosody.tokens(text)
            base = original.run(
                None,
                {
                    "tokens": tokens,
                    "speaker_id": np.array([prosody.meta["speaker_id"]], np.int64),
                    "length_scale": np.array(
                        [prosody.meta["length_scale_default"]], np.float32
                    ),
                },
            )[0]
            actual = prosody.run(text)
            neutral_mels[text] = actual
            difference = float(np.max(np.abs(base - actual)))
            maximum = max(maximum, difference)
            print(language, repr(text), "tokens", tokens.shape[1], "max_abs", difference)

    text = "मदद कीजिए"
    tokens = prosody.tokens(text)
    base_mel = neutral_mels[text]
    biased_mel = prosody.run(text, pitch_bias=np.full(tokens.shape, 30.0, np.float32))
    base_wav = vocoder.run(None, {"mel": base_mel})[0]
    biased_wav = vocoder.run(None, {"mel": biased_mel})[0]
    sample_rate = prosody.meta["sample_rate"]
    hop_length = prosody.meta["hop_length"]
    base_f0 = median_f0(base_wav, sample_rate, hop_length)
    biased_f0 = median_f0(biased_wav, sample_rate, hop_length)
    print("conditioned +30 predictor units (requested as +30 Hz)")
    print("mel_max_abs", float(np.max(np.abs(base_mel - biased_mel))))
    print("mel_mean_abs", float(np.mean(np.abs(base_mel - biased_mel))))
    print("wav_max_abs", float(np.max(np.abs(base_wav - biased_wav))))
    print("wav_mean_abs", float(np.mean(np.abs(base_wav - biased_wav))))
    print("median_f0_hz", base_f0, biased_f0, "shift_hz", biased_f0 - base_f0)
    if maximum > 1e-6:
        raise RuntimeError(f"Neutral graph differs from original: max_abs={maximum}")
    if not np.isfinite(base_f0) or not np.isfinite(biased_f0) or biased_f0 <= base_f0:
        raise RuntimeError("+30 predictor units did not produce a positive measured F0 shift")


if __name__ == "__main__":
    main()
