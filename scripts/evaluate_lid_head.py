"""Compare a trained coarse LID head with legacy Vox routing on held-out FLEURS."""

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch

from sih_speech import VoxLinguaCoarseLID


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--head", type=Path, default=Path("artifacts/lid/indian_english_head.pt"))
    parser.add_argument("--model-dir", type=Path, default=Path("deployment_models/common/lid"))
    parser.add_argument("--benchmark", type=Path, default=Path("evaluation/release_benchmark_20260903"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/lid/fleurs_comparison.json"))
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    rows = []
    for relative in ("indic_fp32/samples.csv", "whisper_fp32/samples.csv"):
        with (args.benchmark / relative).open(encoding="utf-8", newline="") as handle:
            rows.extend(csv.DictReader(handle))
    lid = VoxLinguaCoarseLID(args.model_dir, args.device, binary_head=args.head)
    by_language = defaultdict(lambda: {"samples": 0, "legacy_correct": 0, "head_correct": 0})
    latencies = []
    samples = []
    for index, row in enumerate(rows, 1):
        expected = "english" if row["language_label"] == "english" else "indic"
        result = lid.predict(row["audio_path"])
        item = by_language[row["language_label"]]
        item["samples"] += 1
        item["legacy_correct"] += row["coarse_predicted"] == expected
        item["head_correct"] += result["coarse_group"] == expected
        latencies.append(result["latency_ms"])
        samples.append({
            "audio_path": row["audio_path"], "language": row["language_label"],
            "expected": expected, "legacy": row["coarse_predicted"],
            "head": result["coarse_group"],
            "head_english_score": result["scores"]["english"],
        })
        if index % 100 == 0:
            print(f"Evaluated {index}/{len(rows)}", flush=True)
    summary = {
        "samples": len(rows),
        "legacy_accuracy": sum(v["legacy_correct"] for v in by_language.values()) / len(rows),
        "head_accuracy": sum(v["head_correct"] for v in by_language.values()) / len(rows),
        "head_latency_p50_ms": float(np.percentile(latencies, 50)),
        "head_latency_p95_ms": float(np.percentile(latencies, 95)),
        "per_language": {
            language: {
                **value,
                "legacy_accuracy": value["legacy_correct"] / value["samples"],
                "head_accuracy": value["head_correct"] / value["samples"],
            }
            for language, value in sorted(by_language.items())
        },
        "sample_results": samples,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
