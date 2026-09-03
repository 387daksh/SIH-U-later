"""Prepare a reproducible balanced real-speech FLEURS test subset."""

import argparse
import csv
import hashlib
import random
import shutil
import tarfile
from pathlib import Path

from huggingface_hub import hf_hub_download

FLEURS_REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
CONFIGS = {
    "hindi": "hi_in", "gujarati": "gu_in", "marathi": "mr_in",
    "kannada": "kn_in", "malayalam": "ml_in", "tamil": "ta_in",
    "telugu": "te_in", "odia": "or_in", "bengali": "bn_in",
    "english": "en_us",
}


def choose_rows(rows, count, seed, language):
    rng = random.Random(f"{seed}:{language}")
    chosen = []
    for gender in ("MALE", "FEMALE"):
        group = [row for row in rows if row[-1] == gender]
        chosen.extend(rng.sample(group, min(count // 2, len(group))))
    remaining = [row for row in rows if row not in chosen]
    chosen.extend(rng.sample(remaining, count - len(chosen)))
    return sorted(chosen, key=lambda row: row[1])


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare(samples, seed, source_dir, output_dir):
    source_dir, output_dir = Path(source_dir), Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_rows = []
    for language, config in CONFIGS.items():
        prefix = f"data/{config}"
        metadata = Path(hf_hub_download(
            "google/fleurs", f"{prefix}/test.tsv", repo_type="dataset",
            revision=FLEURS_REVISION, local_dir=source_dir,
        ))
        with metadata.open(encoding="utf-8", newline="") as handle:
            rows = [
                row for row in csv.reader(handle, delimiter="\t")
                if len(row) >= 6 and row[1].endswith(".wav") and row[-1] in {"MALE", "FEMALE"}
            ]
        if len(rows) < samples:
            raise ValueError(f"{config} has only {len(rows)} test rows")
        selected = choose_rows(rows, samples, seed, language)
        wanted = {row[1] for row in selected}
        destination = output_dir / language
        destination.mkdir(exist_ok=True)
        missing = {name for name in wanted if not (destination / name).is_file()}
        if missing:
            print(f"Downloading {language} test archive...", flush=True)
            archive = Path(hf_hub_download(
                "google/fleurs", f"{prefix}/audio/test.tar.gz", repo_type="dataset",
                revision=FLEURS_REVISION, local_dir=source_dir,
            ))
            with tarfile.open(archive, "r:gz") as tar:
                for member in tar:
                    name = Path(member.name).name
                    if name not in missing or not member.isfile():
                        continue
                    source = tar.extractfile(member)
                    with source, (destination / name).open("wb") as target:
                        shutil.copyfileobj(source, target)
                    missing.remove(name)
                    if not missing:
                        break
            if missing:
                raise FileNotFoundError(f"Missing {language} files in archive: {sorted(missing)[:3]}")
        for row in selected:
            path = destination / row[1]
            manifest_rows.append({
                "audio_path": path.relative_to(output_dir).as_posix(),
                "language_label": language,
                "transcript": row[2].strip('"'),
                "raw_transcript": row[3].split("\t", 1)[0].strip('"'),
                "gender": row[-1].lower(),
                "fleurs_id": row[0],
                "sha256": sha256(path),
            })
        print(f"Prepared {samples} {language} recordings.", flush=True)

    manifest = output_dir / "manifest.csv"
    with manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=manifest_rows[0].keys())
        writer.writeheader()
        writer.writerows(manifest_rows)
    (output_dir / "DATASET.md").write_text(
        "# Benchmark data\n\n"
        f"Source: google/fleurs test split at `{FLEURS_REVISION}`.\n\n"
        f"Selection: {samples} recordings per language, seed `{seed}`, balanced "
        "equally by reported binary gender where available.\n\n"
        "License: CC-BY-4.0. Audio and references remain attributed to FLEURS.\n",
        encoding="utf-8",
    )
    print(f"Manifest: {manifest.resolve()}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=100)
    parser.add_argument("--seed", type=int, default=20260901)
    parser.add_argument("--source-dir", default="evaluation/fleurs_source")
    parser.add_argument("--output-dir", default="evaluation/fleurs_test_100")
    args = parser.parse_args()
    prepare(args.samples, args.seed, args.source_dir, args.output_dir)


if __name__ == "__main__":
    main()
