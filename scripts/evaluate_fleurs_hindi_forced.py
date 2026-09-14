"""Evaluate the forced-Hindi IndicConformer path on local FLEURS audio."""

import argparse
import json
from pathlib import Path

from benchmark import read_rows, text_metrics
from sih_speech.pipeline import IndicASR


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("evaluation/fleurs_calibration_30/manifest.csv"))
    parser.add_argument("--model", type=Path, default=Path("indic-conformer-600m-int8-onnx-patched"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/fleurs_hindi_forced_wer.json"))
    args = parser.parse_args()

    rows = [row for row in read_rows(args.manifest) if row["language_label"] == "hindi"]
    if not rows:
        raise ValueError("Manifest contains no Hindi rows")
    asr = IndicASR(args.model)
    results = []
    for index, row in enumerate(rows, 1):
        candidates = asr.transcribe_all(row["audio_path"])["candidates"]
        hypothesis = next(item["text"] for item in candidates if item["language"] == "hindi")
        metrics = text_metrics(row["transcript"], hypothesis)
        metrics["word_matches"] = (
            metrics["reference_words"]
            - metrics["word_substitutions"]
            - metrics["word_deletions"]
        )
        result = {
            "audio_path": row["audio_path"],
            "reference": row["transcript"],
            "hypothesis": hypothesis,
            **{key: value for key, value in metrics.items() if key.startswith("word_") or key == "reference_words"},
        }
        results.append(result)
        print(
            f"[{index}/{len(rows)}] N={metrics['reference_words']} "
            f"M={metrics['word_matches']} S={metrics['word_substitutions']} "
            f"D={metrics['word_deletions']} I={metrics['word_insertions']} "
            f"WER={metrics['word_errors'] / metrics['reference_words']:.4f}",
            flush=True,
        )

    totals = {
        key: sum(row[key] for row in results)
        for key in (
            "reference_words", "word_matches", "word_errors",
            "word_substitutions", "word_deletions", "word_insertions",
        )
    }
    assert totals["word_errors"] == (
        totals["word_substitutions"] + totals["word_deletions"] + totals["word_insertions"]
    )
    assert totals["reference_words"] == (
        totals["word_matches"] + totals["word_substitutions"] + totals["word_deletions"]
    )
    report = {
        "manifest": str(args.manifest.resolve()),
        "model": str(args.model.resolve()),
        "mode": "IndicASR.transcribe_all, forced selection of the Hindi candidate",
        "samples": len(results),
        "totals": {
            **totals,
            "word_accuracy": totals["word_matches"] / totals["reference_words"],
            "wer": totals["word_errors"] / totals["reference_words"],
        },
        "synthetic_wer_for_comparison": 0.32142857142857145,
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report["totals"], ensure_ascii=False), flush=True)
    print(args.output.resolve())


if __name__ == "__main__":
    main()
