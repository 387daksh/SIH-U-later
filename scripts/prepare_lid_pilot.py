"""Prepare speaker-disjoint real audio for the Indian-English coarse LID pilot."""

import argparse
import csv
import hashlib
import json
import tarfile
from collections import Counter, defaultdict
from pathlib import Path

FLEURS_REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
COMMON_VOICE_DATASET = "ishands/commonvoice-indian_accent"
INDIC = {
    "hi_in": "hindi", "gu_in": "gujarati", "mr_in": "marathi",
    "kn_in": "kannada", "ml_in": "malayalam", "ta_in": "tamil",
    "te_in": "telugu", "or_in": "odia", "bn_in": "bengali",
}


def bucket(value, modulo=10):
    return int(hashlib.sha256(value.encode()).hexdigest(), 16) % modulo


def heldout_fleurs_speakers(samples_csv):
    heldout = defaultdict(set)
    with Path(samples_csv).open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            heldout[row["language_label"]].add(row["fleurs_id"])
    return heldout


def select_fleurs_rows(tsv_path, excluded, train_count, val_count):
    by_speaker = defaultdict(list)
    with Path(tsv_path).open(encoding="utf-8", newline="") as handle:
        for row in csv.reader(handle, delimiter="\t"):
            if row[0] not in excluded:
                by_speaker[row[0]].append(row)
    selected = {}
    for split, wanted, is_val in (("train", train_count, False), ("val", val_count, True)):
        rows = []
        speakers = sorted(
            (speaker for speaker in by_speaker if (bucket(speaker, 5) == 0) == is_val),
            key=lambda speaker: hashlib.sha256(speaker.encode()).hexdigest(),
        )
        for speaker in speakers:
            rows.extend(sorted(by_speaker[speaker], key=lambda row: row[1])[:2])
            if len(rows) >= wanted:
                break
        if len(rows) < wanted:
            raise ValueError(f"Only {len(rows)} speaker-disjoint {split} rows available in {tsv_path}")
        selected[split] = rows[:wanted]
    return selected


def prepare_fleurs(args, output, manifest_rows):
    heldout = heldout_fleurs_speakers(args.fleurs_benchmark)
    for config, language in INDIC.items():
        source = args.fleurs_source / "data" / config
        selected = select_fleurs_rows(
            source / "test.tsv", heldout[language],
            args.indic_train_per_language, args.indic_val_per_language,
        )
        wanted = {
            row[1]: (split, row[0])
            for split, rows in selected.items()
            for row in rows
        }
        with tarfile.open(source / "audio" / "test.tar.gz", "r:gz") as archive:
            for member in archive:
                filename = Path(member.name).name
                if filename not in wanted:
                    continue
                split, speaker = wanted.pop(filename)
                target = output / "audio" / language / split / filename
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    source_file = archive.extractfile(member)
                    if source_file is None:
                        raise FileNotFoundError(member.name)
                    target.write_bytes(source_file.read())
                manifest_rows.append({
                    "audio_path": target.relative_to(output).as_posix(),
                    "label": "indic", "language": language, "split": split,
                    "speaker_id": f"fleurs:{config}:{speaker}", "source": "google/fleurs",
                })
        if wanted:
            raise FileNotFoundError(f"Missing {len(wanted)} selected files from {config} archive")
        print(f"Prepared FLEURS {language}: {sum(map(len, selected.values()))} clips", flush=True)


def prepare_english(args, output, manifest_rows):
    from datasets import Audio, load_dataset

    wanted = {"train": args.english_train, "val": args.english_val, "test": args.english_test}
    counts = Counter()
    speaker_counts = Counter()
    dataset = load_dataset(COMMON_VOICE_DATASET, split="train", streaming=True)
    dataset = dataset.cast_column("audio", Audio(decode=False))
    for row in dataset:
        speaker = row.get("client_id")
        duration_ms = row.get("duration_ms") or 0
        if not speaker or not 1_000 <= duration_ms <= 15_000:
            continue
        split_bucket = bucket(speaker)
        split = "test" if split_bucket == 0 else "val" if split_bucket in (1, 2) else "train"
        if counts[split] >= wanted[split] or speaker_counts[(split, speaker)] >= 5:
            continue
        audio = row["audio"]
        if not audio.get("bytes"):
            continue
        suffix = Path(audio.get("path") or "sample.mp3").suffix or ".mp3"
        identity = row.get("sentence_id") or f"{speaker}:{row.get('sentence')}"
        filename = hashlib.sha256(identity.encode()).hexdigest()[:20] + suffix
        target = output / "audio" / "english" / split / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(audio["bytes"])
        manifest_rows.append({
            "audio_path": target.relative_to(output).as_posix(),
            "label": "english", "language": "english", "split": split,
            "speaker_id": f"commonvoice:{speaker}", "source": COMMON_VOICE_DATASET,
        })
        counts[split] += 1
        speaker_counts[(split, speaker)] += 1
        if sum(counts.values()) % 100 == 0:
            print(f"Prepared Common Voice: {sum(counts.values())}/{sum(wanted.values())}", flush=True)
        if all(counts[key] >= value for key, value in wanted.items()):
            break
    if counts != Counter(wanted):
        raise ValueError(f"Common Voice stream ended before quotas were met: {dict(counts)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("evaluation/lid_pilot_data"))
    parser.add_argument("--fleurs-source", type=Path, default=Path("evaluation/fleurs_source"))
    parser.add_argument(
        "--fleurs-benchmark", type=Path,
        default=Path("evaluation/release_benchmark_20260903/indic_fp32/samples.csv"),
    )
    parser.add_argument("--indic-train-per-language", type=int, default=100)
    parser.add_argument("--indic-val-per-language", type=int, default=30)
    parser.add_argument("--english-train", type=int, default=900)
    parser.add_argument("--english-val", type=int, default=270)
    parser.add_argument("--english-test", type=int, default=250)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    prepare_fleurs(args, args.output, rows)
    prepare_english(args, args.output, rows)
    fields = ["audio_path", "label", "language", "split", "speaker_id", "source"]
    with (args.output / "manifest.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fields)
        writer.writeheader()
        writer.writerows(rows)
    metadata = {
        "fleurs_revision": FLEURS_REVISION,
        "fleurs_license": "CC-BY-4.0",
        "common_voice_dataset": COMMON_VOICE_DATASET,
        "common_voice_license": "CC0-1.0",
        "split_rule": "SHA-256 speaker buckets; FLEURS benchmark speakers excluded",
        "counts": {
            f"{label}_{split}": count
            for (label, split), count in Counter((row["label"], row["split"]) for row in rows).items()
        },
    }
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(f"Prepared {len(rows)} real utterances: {args.output / 'manifest.csv'}")


if __name__ == "__main__":
    main()
