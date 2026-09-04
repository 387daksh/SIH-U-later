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
    parser.add_argument("--lid-threshold", type=float)
    parser.add_argument("--lid-head", type=Path)
    parser.add_argument("--whisper-lid-threshold", type=float)
    parser.add_argument("--lid-candidate-threshold", type=float, default=0.3)
    args = parser.parse_args()

    pipeline = SpeechPipeline(
        args.model_root, device="cpu", indic_dir=args.indic_dir,
        lid_confidence_threshold=args.lid_threshold,
        lid_head=args.lid_head,
        whisper_lid_threshold=args.whisper_lid_threshold,
        lid_candidate_threshold=args.lid_candidate_threshold,
    )
    started = time.perf_counter()
    result = pipeline.transcribe(args.audio)
    print(f"Detected language: {result['language']}")
    print(f"ASR backend:       {result['model']}")
    print(f"Transcribed text:  {result['text']}")
    print(f"Inference time:    {time.perf_counter() - started:.2f} seconds")


if __name__ == "__main__":
    main()
