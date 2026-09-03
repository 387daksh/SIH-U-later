"""Benchmark English-vs-Indic LID behavior on real MUCS Hinglish speech."""

import argparse
import hashlib
import io
import json
import math
import statistics
import time
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import psutil
import pyarrow.parquet as pq
import soundfile as sf
from scipy.stats import spearmanr

from sih_speech import VoxLinguaCoarseLID

REVISION = "3c66ee1"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_rows(paths):
    columns = [
        "audio",
        "segment_id",
        "ratio_english_words",
        "ratio_hindi_words",
        "ratio_english_words_range",
    ]
    for path in paths:
        parquet = pq.ParquetFile(path)
        for batch in parquet.iter_batches(batch_size=16, columns=columns):
            yield from batch.to_pylist()


def valid_ratio(row):
    values = row["ratio_english_words"], row["ratio_hindi_words"]
    return all(isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= 1 for value in values)


def percentile(values, q):
    return float(np.percentile(values, q)) if values else 0.0


def aggregate(rows):
    english_routes = sum(row["coarse_group"] == "english" for row in rows)
    return {
        "samples": len(rows),
        "english_route_rate": english_routes / len(rows) if rows else 0.0,
        "indic_route_rate": 1 - english_routes / len(rows) if rows else 0.0,
        "mean_english_score": statistics.fmean(row["scores"]["english"] for row in rows) if rows else 0.0,
        "mean_hindi_score": statistics.fmean(row["scores"]["hindi"] for row in rows) if rows else 0.0,
        "lid_p50_ms": percentile([row["latency_ms"] for row in rows], 50),
        "lid_p95_ms": percentile([row["latency_ms"] for row in rows], 95),
    }


def write_report(summary, path):
    overall = summary["overall"]
    lines = [
        "# Real Hinglish LID benchmark",
        "",
        "MUCS Hindi-English code-switched test speech. VoxLingua has no Hinglish class, so this report characterizes routing behavior rather than claiming monolingual LID accuracy.",
        "",
        "| Metric | Result |",
        "|---|---:|",
        f"| Valid real utterances | {overall['samples']} |",
        f"| Audio hours | {overall['audio_hours']:.2f} |",
        f"| Routed to English | {overall['english_route_rate']:.2%} |",
        f"| Routed to Indic | {overall['indic_route_rate']:.2%} |",
        f"| Dominant-language agreement (ties excluded) | {overall['dominant_group_agreement']:.2%} |",
        f"| English-ratio/English-score Spearman correlation | {overall['english_score_spearman']:.3f} |",
        f"| LID P50 | {overall['lid_p50_ms']:.0f} ms |",
        f"| LID P95 | {overall['lid_p95_ms']:.0f} ms |",
        f"| Peak process memory | {summary['runtime']['peak_process_mib']:.0f} MiB |",
        "",
        "## Routing by annotated English-word ratio",
        "",
        "| English ratio | N | English route | Mean English score | Mean Hindi score |",
        "|---|---:|---:|---:|---:|",
    ]
    for bucket, value in summary["by_english_ratio"].items():
        lines.append(
            f"| {bucket}% | {value['samples']} | {value['english_route_rate']:.2%} | "
            f"{value['mean_english_score']:.2%} | {value['mean_hindi_score']:.2%} |"
        )
    lines.extend([
        "",
        "## Top-label counts",
        "",
        "| Label | Count |",
        "|---|---:|",
    ])
    lines.extend(f"| {label} | {count} |" for label, count in summary["top_labels"].items())
    lines.extend([
        "",
        "## Interpretation limit",
        "",
        "The dominant-language agreement diagnostic compares English-majority clips with the English route and Hindi-majority clips with the Indic route. It is not ground-truth Hinglish accuracy; an utterance-level router cannot represent within-utterance code switching.",
    ])
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(source_dir, model_root, output_dir, quantize):
    started = time.perf_counter()
    source_paths = sorted(Path(source_dir).glob("data/test-*.parquet"))
    if len(source_paths) != 2:
        raise FileNotFoundError(f"Expected two MUCS test parquet files under {source_dir}")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    result_path = output_dir / "samples.jsonl"
    meta = {
        "dataset": "dianavdavidson/MUCS-Hinglish",
        "revision": REVISION,
        "source_sha256": {path.name: sha256(path) for path in source_paths},
        "model_root": str(Path(model_root).resolve()),
        "quantize_lid": quantize,
        "script_sha256": sha256(__file__),
    }
    meta_path = output_dir / "benchmark_meta.json"
    if meta_path.exists() and json.loads(meta_path.read_text(encoding="utf-8")) != meta:
        raise ValueError(f"{output_dir} contains a different benchmark configuration")
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

    completed = set()
    if result_path.exists():
        completed = {
            json.loads(line)["segment_id"]
            for line in result_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        }
    process = psutil.Process()
    rss_before = process.memory_info().rss / 2**20
    load_started = time.perf_counter()
    lid = VoxLinguaCoarseLID(Path(model_root) / "lid", "cpu", quantize=quantize)
    load_seconds = time.perf_counter() - load_started
    rss_after = process.memory_info().rss / 2**20

    invalid = 0
    with result_path.open("a", encoding="utf-8") as output:
        for index, row in enumerate(source_rows(source_paths), 1):
            if not valid_ratio(row):
                invalid += 1
                continue
            if row["segment_id"] in completed:
                continue
            blob = row["audio"]["bytes"]
            info = sf.info(io.BytesIO(blob))
            result = lid.predict(io.BytesIO(blob))
            record = {
                "segment_id": row["segment_id"],
                "audio_sha256": hashlib.sha256(blob).hexdigest(),
                "audio_seconds": info.duration,
                "english_ratio": row["ratio_english_words"],
                "hindi_ratio": row["ratio_hindi_words"],
                "english_ratio_range": row["ratio_english_words_range"],
                **result,
            }
            output.write(json.dumps(record) + "\n")
            output.flush()
            if index % 100 == 0:
                print(f"[{index}/3136]", flush=True)

    rows = [json.loads(line) for line in result_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    by_bucket = defaultdict(list)
    for row in rows:
        by_bucket[row["english_ratio_range"]].append(row)
    dominant = [row for row in rows if row["english_ratio"] != 0.5]
    dominant_correct = sum(
        row["coarse_group"] == ("english" if row["english_ratio"] > 0.5 else "indic")
        for row in dominant
    )
    overall = aggregate(rows)
    overall.update({
        "audio_hours": sum(row["audio_seconds"] for row in rows) / 3600,
        "dominant_language_samples": len(dominant),
        "dominant_group_agreement": dominant_correct / len(dominant),
        "english_score_spearman": float(spearmanr(
            [row["english_ratio"] for row in rows],
            [row["scores"]["english"] for row in rows],
        ).statistic),
    })
    memory = process.memory_info()
    summary = {
        "overall": overall,
        "by_english_ratio": {
            bucket: aggregate(values)
            for bucket, values in sorted(by_bucket.items(), key=lambda item: int(item[0].split("-")[0]))
        },
        "top_labels": dict(Counter(row["legacy_language"] for row in rows).most_common()),
        "invalid_source_rows_excluded": invalid,
        "runtime": {
            "load_seconds": load_seconds,
            "wall_seconds": time.perf_counter() - started,
            "rss_before_load_mib": rss_before,
            "rss_after_load_mib": rss_after,
            "peak_process_mib": getattr(memory, "peak_wset", memory.rss) / 2**20,
        },
    }
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    write_report(summary, output_dir / "REPORT.md")
    print(f"Report: {(output_dir / 'REPORT.md').resolve()}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", default="evaluation/mucs_hinglish_source")
    parser.add_argument("--model-root", default="models")
    parser.add_argument("--output-dir", default="evaluation/results_mucs_hinglish_lid_fp32")
    parser.add_argument("--quantize", action="store_true")
    args = parser.parse_args()
    run(args.source_dir, args.model_root, args.output_dir, args.quantize)


if __name__ == "__main__":
    main()
