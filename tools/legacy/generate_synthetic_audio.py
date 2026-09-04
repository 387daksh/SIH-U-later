"""Legacy network TTS helper for synthetic smoke audio."""

import asyncio
import argparse
from pathlib import Path

import av
import edge_tts
import numpy as np
import soundfile as sf


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", default="नमस्ते, मैं अपने फोन पर test कर रहा हूँ।")
    parser.add_argument("--output", type=Path, default=Path("artifacts/synthetic/hindi_english_test.wav"))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    mp3 = args.output.with_suffix(".mp3")
    await edge_tts.Communicate(args.text, "hi-IN-SwaraNeural").save(str(mp3))
    samples = []
    resampler = av.audio.resampler.AudioResampler(format="flt", layout="mono", rate=16000)
    with av.open(mp3) as container:
        for frame in container.decode(audio=0):
            for converted in resampler.resample(frame):
                samples.append(converted.to_ndarray().reshape(-1))
    audio = np.concatenate(samples).astype("float32")
    sf.write(args.output, audio, 16000)
    print(f"generated={args.output} expected={args.text}")


if __name__ == "__main__":
    asyncio.run(main())
