"""Evaluate Whisper's language token as a secondary English verifier."""

import argparse
import csv
import io
import json
import re
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import psutil
import torch
from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

from scripts.benchmark_hinglish_lid import source_rows, valid_ratio
from sih_speech.pipeline import load_audio


def existing_routes(paths, key):
    rows = {}
    for path in paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            rows[row[key]] = row["coarse_predicted"] if key == "audio_path" else row["coarse_group"]
    return rows


class WhisperLID:
    def __init__(self, model_dir):
        self.processor = AutoProcessor.from_pretrained(model_dir, local_files_only=True)
        self.model = AutoModelForSpeechSeq2Seq.from_pretrained(model_dir, local_files_only=True).eval()
        pairs = []
        for token in self.processor.tokenizer.all_special_tokens:
            if match := re.fullmatch(r"<\|([a-z]{2,3})\|>", token):
                pairs.append((match.group(1), self.processor.tokenizer.convert_tokens_to_ids(token)))
        self.codes, ids = zip(*pairs)
        self.ids = torch.tensor(ids)

    def predict(self, audio):
        started = time.perf_counter()
        waveform = load_audio(audio).numpy()
        inputs = self.processor(waveform, sampling_rate=16000, return_tensors="pt", return_attention_mask=True)
        decoder = torch.tensor([[self.model.config.decoder_start_token_id]])
        with torch.inference_mode():
            logits = self.model(**inputs, decoder_input_ids=decoder).logits[0, -1, self.ids]
            probabilities = logits.softmax(-1)
        best = int(probabilities.argmax())
        return {
            "whisper_language": self.codes[best],
            "whisper_english_score": float(probabilities[self.codes.index("en")]),
            "whisper_lid_ms": (time.perf_counter() - started) * 1000,
        }


def append_result(output, completed, record):
    if record["id"] in completed:
        return
    output.write(json.dumps(record) + "\n")
    output.flush()


def evaluate(rows, threshold):
    fleurs = [row for row in rows if row["source"] == "fleurs"]
    mucs = [row for row in rows if row["source"] == "mucs"]
    route = lambda row: row["vox_route"] == "english" or row["whisper_english_score"] >= threshold
    english = [row for row in fleurs if row["expected"] == "english"]
    indic = [row for row in fleurs if row["expected"] == "indic"]
    majority_en = [row for row in mucs if row["english_ratio"] > 0.5]
    majority_hi = [row for row in mucs if row["english_ratio"] < 0.5]
    return {
        "threshold": threshold,
        "fleurs_accuracy": sum(route(row) == (row["expected"] == "english") for row in fleurs) / len(fleurs),
        "fleurs_english_recall": sum(route(row) for row in english) / len(english),
        "fleurs_indic_specificity": sum(not route(row) for row in indic) / len(indic),
        "mucs_english_majority_route_rate": sum(route(row) for row in majority_en) / len(majority_en),
        "mucs_hindi_majority_indic_rate": sum(not route(row) for row in majority_hi) / len(majority_hi),
    }


def run(args):
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    result_path = output_dir / "samples.jsonl"
    completed = {
        json.loads(line)["id"] for line in result_path.read_text(encoding="utf-8").splitlines()
    } if result_path.exists() else set()
    fleurs_routes = existing_routes([
        args.release_dir / "indic_fp32" / "samples.jsonl",
        args.release_dir / "whisper_fp32" / "samples.jsonl",
    ], "audio_path")
    mucs_routes = existing_routes([args.mucs_results / "samples.jsonl"], "segment_id")

    load_started = time.perf_counter()
    lid = WhisperLID(args.model_root / "whisper")
    load_seconds = time.perf_counter() - load_started
    with result_path.open("a", encoding="utf-8") as output:
        with args.fleurs_manifest.open(encoding="utf-8", newline="") as handle:
            for index, row in enumerate(csv.DictReader(handle), 1):
                path = Path(row["audio_path"])
                if not path.is_absolute():
                    path = args.fleurs_manifest.parent / path
                path = str(path.resolve())
                if f"fleurs:{path}" not in completed:
                    append_result(output, completed, {
                        "id": f"fleurs:{path}", "source": "fleurs", "language": row["language_label"],
                        "expected": "english" if row["language_label"] == "english" else "indic",
                        "vox_route": fleurs_routes[path], **lid.predict(path),
                    })
                if index % 100 == 0:
                    print(f"FLEURS {index}/1000", flush=True)

        source_paths = sorted(args.mucs_source.glob("data/test-*.parquet"))
        for index, row in enumerate(source_rows(source_paths), 1):
            if not valid_ratio(row) or f"mucs:{row['segment_id']}" in completed:
                continue
            append_result(output, completed, {
                "id": f"mucs:{row['segment_id']}", "source": "mucs",
                "english_ratio": row["ratio_english_words"], "hindi_ratio": row["ratio_hindi_words"],
                "english_ratio_range": row["ratio_english_words_range"],
                "vox_route": mucs_routes[row["segment_id"]], **lid.predict(io.BytesIO(row["audio"]["bytes"])),
            })
            if index % 100 == 0:
                print(f"MUCS {index}/3136", flush=True)

    rows = [json.loads(line) for line in result_path.read_text(encoding="utf-8").splitlines()]
    by_language = defaultdict(list)
    for row in rows:
        if row["source"] == "fleurs":
            by_language[row["language"]].append(row)
    summary = {
        "samples": len(rows),
        "thresholds": [evaluate(rows, value) for value in (0.1, 0.2, 0.3, 0.5)],
        "fleurs_english_score_by_language": {
            language: float(np.mean([row["whisper_english_score"] for row in values]))
            for language, values in sorted(by_language.items())
        },
        "latency_ms": {
            "p50": float(np.percentile([row["whisper_lid_ms"] for row in rows], 50)),
            "p95": float(np.percentile([row["whisper_lid_ms"] for row in rows], 95)),
        },
        "load_seconds": load_seconds,
        "peak_process_mib": getattr(psutil.Process().memory_info(), "peak_wset", 0) / 2**20,
    }
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-root", type=Path, default=Path("deployment_models/common"))
    parser.add_argument("--fleurs-manifest", type=Path, default=Path("evaluation/fleurs_test_100/manifest.csv"))
    parser.add_argument("--release-dir", type=Path, default=Path("evaluation/release_benchmark_20260903"))
    parser.add_argument("--mucs-source", type=Path, default=Path("evaluation/mucs_hinglish_source"))
    parser.add_argument("--mucs-results", type=Path, default=Path("evaluation/results_mucs_hinglish_lid_fp32"))
    parser.add_argument("--output-dir", default="evaluation/results_secondary_whisper_lid")
    run(parser.parse_args())
