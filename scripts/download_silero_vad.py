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
    "https://raw.githubusercontent.com/snakers4/silero-vad/v6.2.1/"
    "src/silero_vad/data/silero_vad.onnx"
)
SILERO_VAD_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def verify_model(path: Path) -> bool:
    """Verify that the model file is a valid ONNX graph matching expected I/O."""
    if not path.is_file() or path.stat().st_size < 1_000_000:
        print(f"File {path} does not exist or is too small ({path.stat().st_size if path.exists() else 0} bytes)")
        return False
    if sha256(path) != SILERO_VAD_SHA256:
        print(f"SHA-256 mismatch for {path}")
        return False

    if ort is not None:
        try:
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            input_names = [i.name for i in session.get_inputs()]
            output_names = [o.name for o in session.get_outputs()]
            if input_names != ["input", "state", "sr"] or output_names != ["output", "stateN"]:
                print(f"Unexpected ONNX contract: inputs={input_names}, outputs={output_names}")
                return False
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
    temporary = target.with_suffix(target.suffix + ".download")
    temporary.unlink(missing_ok=True)
    urllib.request.urlretrieve(SILERO_VAD_URL, str(temporary))
    print(f"Downloaded to {temporary} ({temporary.stat().st_size} bytes)")

    if not verify_model(temporary):
        temporary.unlink(missing_ok=True)
        raise RuntimeError("Downloaded model failed verification!")
    temporary.replace(target)

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
