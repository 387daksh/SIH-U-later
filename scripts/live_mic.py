"""Interactive microphone smoke test for the real speech pipeline."""

import argparse
import time
from pathlib import Path

import numpy as np
import sounddevice as sd
import soundfile as sf

from sih_speech import SpeechPipeline, VoxLinguaCoarseLID

SAMPLE_RATE = 16_000


def find_input_device():
    """Use the default mic, falling back to its native 16 kHz Windows endpoint."""
    discard = lambda _data, _frames, _time, _status: None
    try:
        with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32", callback=discard):
            return None
    except sd.PortAudioError as default_error:
        for index, device in enumerate(sd.query_devices()):
            if device["max_input_channels"] and device["default_samplerate"] == SAMPLE_RATE:
                try:
                    with sd.InputStream(
                        device=index, samplerate=SAMPLE_RATE, channels=1,
                        dtype="float32", callback=discard,
                    ):
                        print(f"Default PortAudio endpoint failed ({default_error}); using its 16 kHz endpoint.")
                        return index
                except sd.PortAudioError:
                    pass
        raise default_error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", default="models")
    parser.add_argument("--indic-dir")
    parser.add_argument("--lid-only", action="store_true", help="load and test only VoxLingua LID")
    parser.add_argument("--no-lid", action="store_true", help="skip LID; route by cross-model ASR confidence only")
    args = parser.parse_args()

    input_device = find_input_device()
    microphone = sd.query_devices(input_device, kind="input")["name"]
    print(f"Microphone: {microphone} (16 kHz mono)")
    if args.lid_only:
        print("Loading VoxLingua LID only on CPU...")
        lid = VoxLinguaCoarseLID(Path(args.model_root) / "lid", "cpu")
        pipeline = None
        print("Ready. Scores are relative within the restricted target-language set.\n")
    else:
        label = "IndicConformer + Whisper (no LID, uncalibrated confidence routing)" if args.no_lid \
            else "LID + IndicConformer + Whisper"
        print(f"Loading the real {label} pipeline on CPU...")
        pipeline = SpeechPipeline(args.model_root, device="cpu", indic_dir=args.indic_dir, use_lid=not args.no_lid)
        print("Ready. English uses Whisper; Indic speech uses IndicConformer.\n")

    wav_path = Path("artifacts/recordings/last_mic.wav").resolve()
    wav_path.parent.mkdir(parents=True, exist_ok=True)
    while True:
        try:
            command = input("Press Enter to start recording, or type 'quit': ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\nExiting.")
            break
        if command == "quit":
            break
        if command:
            print("Type only 'quit' or press Enter.")
            continue

        chunks = []

        def capture(indata, _frames, _time_info, status):
            if status:
                print(f"Audio warning: {status}")
            chunks.append(indata.copy())

        print("Recording... speak now.")
        with sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=1,
            dtype="float32",
            device=input_device,
            callback=capture,
        ):
            stop_command = input("Press Enter to stop: ").strip().lower()

        if stop_command == "quit":
            break
        if not chunks:
            print("No audio was captured; try again.\n")
            continue

        stopped = time.perf_counter()
        audio = np.concatenate(chunks, axis=0)
        if not np.any(audio):
            print("The microphone returned digital silence; check Windows microphone privacy/mute settings.\n")
            continue
        sf.write(wav_path, audio, SAMPLE_RATE, subtype="PCM_16")
        if args.lid_only:
            result = lid.predict(wav_path)
            latency = time.perf_counter() - stopped
            scores = sorted(result["scores"].items(), key=lambda item: item[1], reverse=True)
            backend = "Whisper" if result["coarse_group"] == "english" else "IndicConformer"
            print(f"Saved recording:   {wav_path}")
            print(f"Top LID label:     {result['legacy_language']} ({result['confidence']:.1%})")
            print(f"English score:     {result['scores']['english']:.1%}")
            print(f"Hindi score:       {result['scores']['hindi']:.1%}")
            print("Top scores:        " + ", ".join(f"{name}={score:.1%}" for name, score in scores[:4]))
            print(f"Routing decision:  {result['coarse_group']} -> {backend}")
            print(f"LID inference:     {result['latency_ms']:.0f} ms")
            print(f"Stop-to-decision:  {latency:.2f} seconds\n")
            continue
        try:
            result = pipeline.transcribe(wav_path)
        except Exception as error:
            print(f"Pipeline failed: {error}\n")
            continue
        latency = time.perf_counter() - stopped
        print(f"Saved recording:   {wav_path}")
        if result["lid_confidence"] is not None:
            scores = sorted(result["lid_scores"].items(), key=lambda item: item[1], reverse=True)
            print(f"Vox coarse guess:  {result['legacy_language']} ({result['lid_confidence']:.1%})")
            print("Vox top scores:    " + ", ".join(f"{name}={score:.1%}" for name, score in scores[:4]))
        print(f"Detected language: {result['language']}")
        print(f"ASR backend:       {result['model']}")
        print(f"Routing decision:  {result['decision']} (asr_confidence={result['asr_confidence']:.3f})")
        print(f"Transcribed text:  {result['text']}")
        print(f"Stop-to-text time: {latency:.2f} seconds\n")


if __name__ == "__main__":
    main()
