"""Calibrate the Whisper-vs-IndicConformer confidence fallback on real audio
with known gold language labels, instead of trusting an unvalidated `>=`
comparison between two differently-scaled confidence metrics.

Collects, for every manifest row: the LID's own coarse guess/confidence, and
both ASR engines' self-reported confidence — then scores several candidate
routing rules against the gold coarse label (english vs indic) to find one
that's actually safe to ship.
"""

import argparse
import csv
import json
from pathlib import Path

from sih_speech import IndicASR, VoxLinguaCoarseLID, WhisperASR


def read_manifest(path):
    manifest = Path(path)
    rows = []
    with manifest.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            audio_path = row["audio_path"]
            if not Path(audio_path).is_absolute():
                audio_path = manifest.parent / audio_path
            rows.append({"audio_path": str(audio_path), "language": row["language_label"]})
    return rows


def collect(manifest_path, model_root, indic_dir, output):
    rows = read_manifest(manifest_path)
    lid = VoxLinguaCoarseLID(Path(model_root) / "lid", "cpu")
    whisper = WhisperASR(Path(model_root) / "whisper", "cpu")
    indic = IndicASR(indic_dir)

    records = []
    output_path = Path(output)
    if output_path.exists():
        records = json.loads(output_path.read_text(encoding="utf-8"))
    done = {record["audio_path"] for record in records}

    for index, row in enumerate(rows, 1):
        if row["audio_path"] in done:
            continue
        expected = row["language"]
        expected_coarse = "english" if expected == "english" else "indic"
        lid_result = lid.predict(row["audio_path"])
        whisper_result = whisper.transcribe(row["audio_path"])
        indic_result = indic.transcribe_all(row["audio_path"])
        record = {
            "audio_path": row["audio_path"],
            "expected": expected,
            "expected_coarse": expected_coarse,
            "lid_coarse_group": lid_result["coarse_group"],
            "lid_confidence": lid_result["confidence"],
            "whisper_confidence": whisper_result["confidence"],
            "indic_confidence": indic_result["confidence"],
            "indic_predicted_language": indic_result["language"],
        }
        records.append(record)
        print(
            f"[{index}/{len(rows)}] {expected:10s} "
            f"lid={lid_result['confidence']:.3f}({lid_result['coarse_group']:6s}) "
            f"whisper={whisper_result['confidence']:.3f} indic={indic_result['confidence']:.3f}",
            flush=True,
        )
        output_path.write_text(json.dumps(records, indent=2), encoding="utf-8")
    return records


def rule_lid_argmax(record):
    return record["lid_coarse_group"]


def rule_raw_confidence(record):
    return "english" if record["whisper_confidence"] >= record["indic_confidence"] else "indic"


def rule_lid_primary(record, threshold):
    if record["lid_confidence"] >= threshold:
        return record["lid_coarse_group"]
    return rule_raw_confidence(record)


def rule_lid_default_with_margin(record, threshold, margin):
    """Only let the ASR comparison override the LID pick when uncertain AND
    the challenger's confidence exceeds the LID-favoured engine's by `margin`."""
    if record["lid_confidence"] >= threshold:
        return record["lid_coarse_group"]
    favoured = record["lid_coarse_group"]
    favoured_conf = record["whisper_confidence"] if favoured == "english" else record["indic_confidence"]
    challenger_conf = record["indic_confidence"] if favoured == "english" else record["whisper_confidence"]
    challenger = "indic" if favoured == "english" else "english"
    if challenger_conf >= favoured_conf + margin:
        return challenger
    return favoured


def accuracy(records, rule):
    correct = sum(rule(record) == record["expected_coarse"] for record in records)
    return correct / len(records)


def confusion(records, rule):
    counts = {"english->english": 0, "english->indic": 0, "indic->english": 0, "indic->indic": 0}
    for record in records:
        predicted = rule(record)
        counts[f"{record['expected_coarse']}->{predicted}"] += 1
    return counts


def analyze(records):
    print(f"\n{len(records)} calibration samples "
          f"({sum(r['expected_coarse'] == 'english' for r in records)} english, "
          f"{sum(r['expected_coarse'] == 'indic' for r in records)} indic)\n")

    candidates = [
        ("lid-argmax-always (current legacy metric)", rule_lid_argmax),
        ("raw-confidence-compare (current fallback, no LID)", rule_raw_confidence),
    ]
    for threshold in (0.3, 0.5, 0.6, 0.7, 0.8, 0.9):
        candidates.append((
            f"lid-primary(threshold={threshold}) + raw-fallback",
            lambda record, t=threshold: rule_lid_primary(record, t),
        ))
    for threshold in (0.5, 0.6, 0.7):
        for margin in (0.1, 0.2, 0.3):
            candidates.append((
                f"lid-default(threshold={threshold}) + margin-override(margin={margin})",
                lambda record, t=threshold, m=margin: rule_lid_default_with_margin(record, t, m),
            ))

    results = sorted(
        ((name, accuracy(records, rule), rule) for name, rule in candidates),
        key=lambda item: -item[1],
    )
    print(f"{'rule':60s} {'coarse accuracy':>16s}")
    for name, acc, _ in results:
        print(f"{name:60s} {acc:16.4f}")

    best_name, best_acc, best_rule = results[0]
    print(f"\nBest rule: {best_name} ({best_acc:.4f})")
    print("Confusion:", confusion(records, best_rule))
    print("\nFor reference, current shipped behaviour:")
    print("  lid-confident(0.6)+raw-fallback:", confusion(
        records, lambda r: rule_lid_primary(r, 0.6),
    ))


def main():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    collect_parser = subparsers.add_parser("collect")
    collect_parser.add_argument("--manifest", required=True)
    collect_parser.add_argument("--model-root", default="models")
    collect_parser.add_argument("--indic-dir", default="models/indic")
    collect_parser.add_argument("--output", default="evaluation/routing_calibration/records.json")
    analyze_parser = subparsers.add_parser("analyze")
    analyze_parser.add_argument("--records", default="evaluation/routing_calibration/records.json")
    args = parser.parse_args()
    if args.command == "collect":
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        records = collect(args.manifest, args.model_root, args.indic_dir, args.output)
    else:
        records = json.loads(Path(args.records).read_text(encoding="utf-8"))
    analyze(records)


if __name__ == "__main__":
    main()
