"""Transcribe one local audio file through the real LID-routed pipeline."""

import argparse
import time
from pathlib import Path

from sih_speech import SpeechPipeline


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audio", type=Path, help="Path to a WAV, FLAC, MP3, or other soundfile-readable file")
    parser.add_argument("--model-root", type=Path, default=Path("deployment_models/common"))
    parser.add_argument("--indic-dir", type=Path, default=Path("deployment_models/indic/fp32"))
    args = parser.parse_args()

    pipeline = SpeechPipeline(args.model_root, device="cpu", indic_dir=args.indic_dir)
    started = time.perf_counter()
    result = pipeline.transcribe(args.audio)
    print(f"Detected language: {result['language']}")
    print(f"ASR backend:       {result['model']}")
    print(f"Transcribed text:  {result['text']}")
    print(f"Inference time:    {time.perf_counter() - started:.2f} seconds")


if __name__ == "__main__":
    main()
