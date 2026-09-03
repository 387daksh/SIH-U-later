"""Run the resumable real-speech benchmark and write evaluation reports."""

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
import time
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import psutil
import soundfile as sf

os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")

from sih_speech import INDIC_CODES, LANGUAGES, WHISPER_ID, SpeechPipeline  # noqa: E402


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize(text):
    text = unicodedata.normalize("NFC", text).casefold()
    text = "".join(" " if unicodedata.category(char)[0] in "PS" else char for char in text)
    return " ".join(text.split())


def edits(reference, hypothesis):
    previous = [(index, 0, 0, index) for index in range(len(hypothesis) + 1)]
    for row_index, expected in enumerate(reference, 1):
        current = [(row_index, 0, row_index, 0)]
        for column_index, actual in enumerate(hypothesis, 1):
            if expected == actual:
                current.append(previous[column_index - 1])
                continue
            substitution = previous[column_index - 1]
            deletion = previous[column_index]
            insertion = current[column_index - 1]
            current.append(min(
                (substitution[0] + 1, substitution[1] + 1, substitution[2], substitution[3]),
                (deletion[0] + 1, deletion[1], deletion[2] + 1, deletion[3]),
                (insertion[0] + 1, insertion[1], insertion[2], insertion[3] + 1),
                key=lambda value: value[0],
            ))
        previous = current
    return previous[-1]


def text_metrics(reference, hypothesis):
    reference, hypothesis = normalize(reference), normalize(hypothesis)
    words = edits(reference.split(), hypothesis.split())
    characters = edits(list(reference.replace(" ", "")), list(hypothesis.replace(" ", "")))
    return {
        "word_errors": words[0], "word_substitutions": words[1],
        "word_deletions": words[2], "word_insertions": words[3],
        "reference_words": len(reference.split()),
        "character_errors": characters[0], "character_substitutions": characters[1],
        "character_deletions": characters[2], "character_insertions": characters[3],
        "reference_characters": len(reference.replace(" ", "")),
        "sentence_error": int(reference != hypothesis),
    }


def read_rows(manifest):
    manifest = Path(manifest)
    rows = []
    with manifest.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            path = Path(row["audio_path"])
            if not path.is_absolute():
                path = manifest.parent / path
            rows.append({**row, "audio_path": str(path.resolve())})
    return rows


def load_completed(path):
    completed = {}
    if not path.exists():
        return completed
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                print(f"Ignoring incomplete result line {line_number}.")
                continue
            completed[row["audio_path"]] = row
    return completed


def run_benchmark(manifest, model_root, indic_dir, output_dir):
    manifest, output_dir = Path(manifest), Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    result_path = output_dir / "samples.jsonl"
    rows = read_rows(manifest)
    completed = load_completed(result_path)
    meta = {
        "manifest": str(manifest.resolve()),
        "manifest_sha256": file_sha256(manifest),
        "model_root": str(Path(model_root).resolve()),
        "indic_dir": str(Path(indic_dir).resolve()),
        "pipeline_sha256": file_sha256("sih_speech/pipeline.py"),
        "device": "cpu",
    }
    meta_path = output_dir / "benchmark_meta.json"
    pending = [row for row in rows if row["audio_path"] not in completed]
    existing_meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else None
    if existing_meta and existing_meta != meta and pending:
        raise ValueError(f"{output_dir} contains results from a different benchmark configuration")
    if not existing_meta:
        meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    if pending:
        print(f"Loading pipeline; {len(pending)} of {len(rows)} samples remain...", flush=True)
        load_started = time.perf_counter()
        pipeline = SpeechPipeline(model_root, device="cpu", indic_dir=indic_dir)
        print(f"Pipeline loaded in {time.perf_counter() - load_started:.2f}s.", flush=True)
        with result_path.open("a", encoding="utf-8") as output:
            for index, row in enumerate(pending, len(completed) + 1):
                audio_path, expected = row["audio_path"], row["language_label"]
                info = sf.info(audio_path)
                started = time.perf_counter()
                routed = pipeline.transcribe(audio_path)
                routed_ms = (time.perf_counter() - started) * 1000
                indic_result = routed if routed["model"] != WHISPER_ID else None
                oracle_extra_ms = 0.0
                if expected == "english":
                    if routed["model"] == WHISPER_ID:
                        oracle_text = routed["text"]
                    else:
                        started = time.perf_counter()
                        oracle_text = pipeline.whisper.transcribe(audio_path)
                        oracle_extra_ms = (time.perf_counter() - started) * 1000
                else:
                    if indic_result is None:
                        started = time.perf_counter()
                        indic_result = pipeline.indic.transcribe_all(audio_path)
                        oracle_extra_ms = (time.perf_counter() - started) * 1000
                    oracle_text = next(
                        candidate["text"] for candidate in indic_result["candidates"]
                        if candidate["language"] == expected
                    )
                record = {
                    **row,
                    "audio_seconds": info.frames / info.samplerate,
                    "predicted_language": routed["language"],
                    "coarse_expected": "english" if expected == "english" else "indic",
                    "coarse_predicted": "english" if routed["model"] == WHISPER_ID else "indic",
                    "legacy_language": routed["legacy_language"],
                    "lid_confidence": routed["lid_confidence"],
                    "lid_scores": routed["lid_scores"],
                    "routed_text": routed["text"],
                    "oracle_text": oracle_text,
                    "routed_model": routed["model"],
                    "routed_ms": routed_ms,
                    "lid_ms": routed["lid_latency_ms"],
                    "routed_asr_ms": routed_ms - routed["lid_latency_ms"],
                    "oracle_extra_ms": oracle_extra_ms,
                    "indic_confidence": indic_result["confidence"] if indic_result else None,
                    "indic_candidates": indic_result["candidates"] if indic_result else [],
                }
                for prefix, hypothesis in (("routed", routed["text"]), ("oracle", oracle_text)):
                    record.update({f"{prefix}_{key}": value for key, value in text_metrics(row["transcript"], hypothesis).items()})
                output.write(json.dumps(record, ensure_ascii=False) + "\n")
                output.flush()
                completed[audio_path] = record
                sample_wer = record["routed_word_errors"] / max(record["routed_reference_words"], 1)
                print(
                    f"[{index}/{len(rows)}] {expected}->{record['predicted_language']} "
                    f"WER={sample_wer:.2f} {routed_ms:.0f}ms",
                    flush=True,
                )
    summarize(list(completed.values()), output_dir)


def ratio(numerator, denominator):
    return numerator / denominator if denominator else 0.0


def wilson(correct, total):
    if not total:
        return [0.0, 0.0]
    z = 1.959963984540054
    proportion = correct / total
    denominator = 1 + z * z / total
    center = (proportion + z * z / (2 * total)) / denominator
    margin = z * math.sqrt(proportion * (1 - proportion) / total + z * z / (4 * total * total)) / denominator
    return [center - margin, center + margin]


def percentile(values, value):
    return float(np.percentile(values, value)) if values else 0.0


def aggregate(rows):
    result = {"samples": len(rows), "audio_hours": sum(row["audio_seconds"] for row in rows) / 3600}
    for prefix in ("routed", "oracle"):
        for unit in ("word", "character"):
            for operation in ("errors", "substitutions", "deletions", "insertions"):
                key = f"{unit}_{operation}"
                result[f"{prefix}_{key}"] = sum(row[f"{prefix}_{key}"] for row in rows)
        word_errors = result[f"{prefix}_word_errors"]
        reference_words = sum(row[f"{prefix}_reference_words"] for row in rows)
        character_errors = result[f"{prefix}_character_errors"]
        reference_characters = sum(row[f"{prefix}_reference_characters"] for row in rows)
        result[f"{prefix}_wer"] = ratio(word_errors, reference_words)
        result[f"{prefix}_cer"] = ratio(character_errors, reference_characters)
        result[f"{prefix}_ser"] = ratio(sum(row[f"{prefix}_sentence_error"] for row in rows), len(rows))
        result[f"{prefix}_word_errors"] = word_errors
        result[f"{prefix}_reference_words"] = reference_words
    correct = sum(row["predicted_language"] == row["language_label"] for row in rows)
    legacy_correct = sum(row["legacy_language"] == row["language_label"] for row in rows)
    coarse_correct = sum(row["coarse_predicted"] == row["coarse_expected"] for row in rows)
    result.update({
        "language_accuracy": ratio(correct, len(rows)),
        "language_accuracy_95ci": wilson(correct, len(rows)),
        "legacy_accuracy": ratio(legacy_correct, len(rows)),
        "coarse_accuracy": ratio(coarse_correct, len(rows)),
        "routed_latency_ms": {
            "mean": statistics.fmean(row["routed_ms"] for row in rows),
            "p50": percentile([row["routed_ms"] for row in rows], 50),
            "p95": percentile([row["routed_ms"] for row in rows], 95),
            "max": max(row["routed_ms"] for row in rows),
        },
        "lid_latency_ms": {
            "mean": statistics.fmean(row["lid_ms"] for row in rows),
            "p50": percentile([row["lid_ms"] for row in rows], 50),
            "p95": percentile([row["lid_ms"] for row in rows], 95),
        },
        "routed_asr_latency_ms": {
            "mean": statistics.fmean(row["routed_asr_ms"] for row in rows),
            "p50": percentile([row["routed_asr_ms"] for row in rows], 50),
            "p95": percentile([row["routed_asr_ms"] for row in rows], 95),
        },
        "aggregate_rtf": ratio(sum(row["routed_ms"] for row in rows) / 1000, sum(row["audio_seconds"] for row in rows)),
    })
    return result


def classification(rows):
    matrix = {expected: {predicted: 0 for predicted in LANGUAGES} for expected in LANGUAGES}
    for row in rows:
        matrix[row["language_label"]][row["predicted_language"]] += 1
    metrics = {}
    for language in LANGUAGES:
        true_positive = matrix[language][language]
        false_negative = sum(matrix[language].values()) - true_positive
        false_positive = sum(matrix[other][language] for other in LANGUAGES if other != language)
        precision = ratio(true_positive, true_positive + false_positive)
        recall = ratio(true_positive, true_positive + false_negative)
        metrics[language] = {
            "precision": precision,
            "recall": recall,
            "f1": ratio(2 * precision * recall, precision + recall),
            "support": true_positive + false_negative,
        }
    metrics["macro"] = {
        key: statistics.fmean(metrics[language][key] for language in LANGUAGES)
        for key in ("precision", "recall", "f1")
    }
    return matrix, metrics


def expected_calibration_error(rows, bins=10):
    totals = [0] * bins
    correct = [0] * bins
    confidences = [0.0] * bins
    for row in rows:
        index = min(int(row["lid_confidence"] * bins), bins - 1)
        totals[index] += 1
        correct[index] += row["legacy_language"] == row["language_label"]
        confidences[index] += row["lid_confidence"]
    count = len(rows)
    return sum(
        totals[index] / count * abs(correct[index] / totals[index] - confidences[index] / totals[index])
        for index in range(bins) if totals[index]
    )


def summarize(rows, output_dir):
    rows.sort(key=lambda row: (LANGUAGES.index(row["language_label"]), row["audio_path"]))
    per_language = {
        language: aggregate([row for row in rows if row["language_label"] == language])
        for language in LANGUAGES
    }
    overall = aggregate(rows)
    overall["macro_routed_wer"] = statistics.fmean(value["routed_wer"] for value in per_language.values())
    overall["macro_routed_cer"] = statistics.fmean(value["routed_cer"] for value in per_language.values())
    overall["macro_oracle_wer"] = statistics.fmean(value["oracle_wer"] for value in per_language.values())
    overall["macro_oracle_cer"] = statistics.fmean(value["oracle_cer"] for value in per_language.values())
    overall["legacy_lid_ece"] = expected_calibration_error(rows)
    output_dir = Path(output_dir)
    previous_summary_path = output_dir / "summary.json"
    previous_summary = json.loads(previous_summary_path.read_text(encoding="utf-8")) if previous_summary_path.exists() else {}
    memory = psutil.Process().memory_info()._asdict()
    memory_bytes = {
        key: value / 2**20 for key, value in memory.items()
        if key != "num_page_faults"
    }
    previous_memory = previous_summary.get("process_memory_mib", {})
    if previous_memory.get("peak_wset", 0) > memory_bytes.get("peak_wset", 0):
        memory_bytes = previous_memory
    matrix, class_metrics = classification(rows)
    genders = {
        gender: aggregate([row for row in rows if row.get("gender") == gender])
        for gender in sorted({row.get("gender") for row in rows})
    }
    summary = {
        "overall": overall,
        "per_language": per_language,
        "classification": class_metrics,
        "confusion_matrix": matrix,
        "per_gender": genders,
        "process_memory_mib": memory_bytes,
        "process_page_faults": memory.get("num_page_faults"),
    }
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_csv(rows, output_dir / "samples.csv")
    write_report(summary, output_dir / "REPORT.md")
    print(f"Report: {(output_dir / 'REPORT.md').resolve()}")


def write_csv(rows, path):
    excluded = {"lid_scores", "indic_candidates"}
    fields = [key for key in rows[0] if key not in excluded]
    with Path(path).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def percent(value):
    return f"{value * 100:.2f}%"


def write_report(summary, path):
    overall = summary["overall"]
    lines = [
        "# Real multilingual pipeline benchmark", "",
        f"FLEURS test speech; {min(value['samples'] for value in summary['per_language'].values())} deterministic recordings per language. Results are corpus-level unless stated otherwise.", "",
        "## Overall", "",
        "| Metric | Result |", "|---|---:|",
        f"| Samples | {overall['samples']} |",
        f"| Audio hours | {overall['audio_hours']:.2f} |",
        f"| Final language accuracy | {percent(overall['language_accuracy'])} |",
        f"| Final language accuracy 95% CI | {percent(overall['language_accuracy_95ci'][0])}–{percent(overall['language_accuracy_95ci'][1])} |",
        f"| Legacy restricted-Vox accuracy | {percent(overall['legacy_accuracy'])} |",
        f"| Coarse English/Indic accuracy | {percent(overall['coarse_accuracy'])} |",
        f"| Macro language F1 | {percent(summary['classification']['macro']['f1'])} |",
        f"| Macro routed WER | {percent(overall['macro_routed_wer'])} |",
        f"| Micro routed WER | {percent(overall['routed_wer'])} |",
        f"| Macro routed CER | {percent(overall['macro_routed_cer'])} |",
        f"| Micro routed CER | {percent(overall['routed_cer'])} |",
        f"| Macro oracle WER | {percent(overall['macro_oracle_wer'])} |",
        f"| Micro oracle WER | {percent(overall['oracle_wer'])} |",
        f"| Macro oracle CER | {percent(overall['macro_oracle_cer'])} |",
        f"| Micro oracle CER | {percent(overall['oracle_cer'])} |",
        f"| Sentence error rate | {percent(overall['routed_ser'])} |", "",
        "## Per-language results", "",
        "| Language | Legacy Vox acc. | Final LID acc. | Routed WER | Routed CER | Oracle WER | Oracle CER | P50 ms | P95 ms |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for language in LANGUAGES:
        value = summary["per_language"][language]
        lines.append(
            f"| {language} | {percent(value['legacy_accuracy'])} | {percent(value['language_accuracy'])} | {percent(value['routed_wer'])} | "
            f"{percent(value['routed_cer'])} | {percent(value['oracle_wer'])} | {percent(value['oracle_cer'])} | "
            f"{value['routed_latency_ms']['p50']:.0f} | {value['routed_latency_ms']['p95']:.0f} |"
        )
    lines.extend(["", "## Language classification", "", "| Language | Precision | Recall | F1 | N |", "|---|---:|---:|---:|---:|"])
    for language in LANGUAGES:
        value = summary["classification"][language]
        lines.append(f"| {language} | {percent(value['precision'])} | {percent(value['recall'])} | {percent(value['f1'])} | {value['support']} |")
    lines.extend(["", "## Final-language confusion matrix", "", "Rows are references; columns are predictions.", ""])
    lines.append("| ref \\ pred | " + " | ".join(LANGUAGES) + " |")
    lines.append("|---|" + "---:|" * len(LANGUAGES))
    for expected in LANGUAGES:
        lines.append(f"| {expected} | " + " | ".join(str(summary["confusion_matrix"][expected][predicted]) for predicted in LANGUAGES) + " |")
    lines.extend([
        "", "## Timing", "",
        "| Stage | Mean ms | P50 ms | P95 ms |", "|---|---:|---:|---:|",
        f"| Complete routed pipeline | {overall['routed_latency_ms']['mean']:.0f} | {overall['routed_latency_ms']['p50']:.0f} | {overall['routed_latency_ms']['p95']:.0f} |",
        f"| VoxLingua LID | {overall['lid_latency_ms']['mean']:.0f} | {overall['lid_latency_ms']['p50']:.0f} | {overall['lid_latency_ms']['p95']:.0f} |",
        f"| Routed ASR after LID | {overall['routed_asr_latency_ms']['mean']:.0f} | {overall['routed_asr_latency_ms']['p50']:.0f} | {overall['routed_asr_latency_ms']['p95']:.0f} |",
        f"| Aggregate real-time factor | {overall['aggregate_rtf']:.3f} |  |  |", "",
        "## Error and runtime details", "",
        "| Metric | Value |", "|---|---:|",
        f"| Routed word substitutions | {overall['routed_word_substitutions']} |",
        f"| Routed word deletions | {overall['routed_word_deletions']} |",
        f"| Routed word insertions | {overall['routed_word_insertions']} |",
        f"| Legacy LID expected calibration error | {percent(overall['legacy_lid_ece'])} |",
        f"| Peak process working set | {summary['process_memory_mib'].get('peak_wset', 0):.0f} MiB |", "",
        "## Metric protocol", "",
        "Text is Unicode NFC normalized and case-folded; punctuation/symbols are converted to spaces. WER uses whitespace tokens. CER excludes spaces but preserves Indic combining marks. Routed metrics measure the actual automatic pipeline. Oracle metrics force the known reference language while keeping the same ASR model, isolating routing errors.", "",
        "## Limits", "",
        "FLEURS is clean read speech, not conversational microphone speech. This benchmark measures one deterministic test subset and this desktop CPU/ONNX build; it is not Android latency evidence.",
    ])
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="evaluation/fleurs_test_100/manifest.csv")
    parser.add_argument("--model-root", default="models")
    parser.add_argument("--indic-dir", default="indic-conformer-600m-int8-onnx-patched")
    parser.add_argument("--output-dir", default="evaluation/results_fleurs_test_100")
    args = parser.parse_args()
    run_benchmark(args.manifest, args.model_root, args.indic_dir, args.output_dir)


if __name__ == "__main__":
    main()
