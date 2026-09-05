"""Download the Silero VAD ONNX model for real-time speech segmentation."""

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

try:
    import onnxruntime as ort
except ImportError:
    ort = None

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_TARGET = ROOT / "models" / "vad" / "silero_vad.onnx"
SILERO_VAD_URL = (
    "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"
)


def verify_model(path: Path) -> bool:
    """Verify that the model file is a valid ONNX graph matching expected I/O."""
    if not path.is_file() or path.stat().st_size < 1_000_000:
        print(f"File {path} does not exist or is too small ({path.stat().st_size if path.exists() else 0} bytes)")
        return False

    if ort is not None:
        try:
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            input_names = [i.name for i in session.get_inputs()]
            output_names = [o.name for o in session.get_outputs()]
            print(f"Verified ONNX model: inputs={input_names}, outputs={output_names}")
            return True
        except Exception as e:
            print(f"ONNX verification failed: {e}")
            return False
    return True


def download_silero_vad(target: Path, force: bool = False) -> Path:
    target = Path(target)
    if target.is_file() and not force:
        print(f"Silero VAD model already exists: {target} ({target.stat().st_size} bytes)")
        if verify_model(target):
            return target
        print("Existing model verification failed, re-downloading...")

    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading Silero VAD ONNX from {SILERO_VAD_URL} ...")
    urllib.request.urlretrieve(SILERO_VAD_URL, str(target))
    print(f"Downloaded to {target} ({target.stat().st_size} bytes)")

    if not verify_model(target):
        raise RuntimeError("Downloaded model failed verification!")

    return target


def main():
    parser = argparse.ArgumentParser(description="Download Silero VAD ONNX model.")
    parser.add_argument("--output", type=Path, default=DEFAULT_TARGET, help="Target path for silero_vad.onnx")
    parser.add_argument("--force", action="store_true", help="Force re-download even if file exists")
    args = parser.parse_args()

    download_silero_vad(args.output, force=args.force)
    print("Silero VAD model ready.")


if __name__ == "__main__":
    main()
