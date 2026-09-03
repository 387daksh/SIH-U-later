"""Run one direct transcription through the patched Indic ONNX model."""

import argparse
import sys
from pathlib import Path

import soundfile as sf
import torch


ROOT = Path(__file__).resolve().parents[1] / "indic-conformer-600m-int8-onnx-patched"
sys.path.insert(0, str(ROOT))
import model_onnx  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="Transcribe a 16 kHz mono WAV file.")
    parser.add_argument("wav", type=Path)
    parser.add_argument("language", help="For example: hi, bn, ta, te, mr")
    args = parser.parse_args()

    audio, sample_rate = sf.read(args.wav, dtype="float32")
    if sample_rate != 16000:
        raise SystemExit(f"Expected 16000 Hz WAV, got {sample_rate} Hz")
    if audio.ndim == 2:
        audio = audio.mean(axis=1)
    if audio.ndim != 1:
        raise SystemExit("Expected mono or stereo WAV audio")

    model = model_onnx.IndicASRModel(
        model_onnx.IndicASRConfig(ts_folder=str(ROOT), FRAME_DURATION_MS=0.08)
    )
    with torch.no_grad():
        text = model(torch.from_numpy(audio).unsqueeze(0), args.language, "ctc")
    print(text)


if __name__ == "__main__":
    main()
