"""Train and validate a tiny English-vs-Indic head on frozen ECAPA embeddings."""

import argparse
import csv
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import torch
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import balanced_accuracy_score, confusion_matrix, roc_auc_score
from sklearn.preprocessing import StandardScaler

from sih_speech import FrozenECAPA, ecapa_utterance_embedding, load_audio


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_rows(manifest):
    root = Path(manifest).parent
    with Path(manifest).open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        row["audio_path"] = str((root / row["audio_path"]).resolve())
    return rows


def embeddings(rows, extractor, device, cache_path, manifest_hash):
    if cache_path.exists():
        cached = np.load(cache_path, allow_pickle=False)
        if str(cached["manifest_hash"]) == manifest_hash:
            print(f"Using cached embeddings: {cache_path}")
            return cached["x"]
    vectors = []
    started = time.perf_counter()
    with torch.inference_mode():
        for index, row in enumerate(rows, 1):
            waveform = load_audio(row["audio_path"])
            vector = ecapa_utterance_embedding(extractor, waveform, device)
            vectors.append(vector.cpu().numpy())
            if index % 100 == 0:
                print(f"Embedded {index}/{len(rows)}", flush=True)
    matrix = np.asarray(vectors, dtype=np.float32)
    np.savez_compressed(cache_path, x=matrix, manifest_hash=manifest_hash)
    print(f"Embedding time: {time.perf_counter() - started:.1f}s")
    return matrix


def metrics(y, probabilities, threshold):
    predicted = (probabilities >= threshold).astype(int)
    matrix = confusion_matrix(y, predicted, labels=[0, 1])
    present = set(y.tolist())
    return {
        "samples": len(y),
        "accuracy": float((predicted == y).mean()),
        "balanced_accuracy": float(balanced_accuracy_score(y, predicted)) if len(present) == 2 else None,
        "roc_auc": float(roc_auc_score(y, probabilities)) if len(present) == 2 else None,
        "indic_recall": float(matrix[0, 0] / matrix[0].sum()) if 0 in present else None,
        "english_recall": float(matrix[1, 1] / matrix[1].sum()) if 1 in present else None,
        "confusion_indic_english": matrix.tolist(),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("evaluation/lid_pilot_data/manifest.csv"))
    parser.add_argument("--model-dir", type=Path, default=Path("deployment_models/common/lid"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/lid/indian_english_head.pt"))
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    rows = read_rows(args.manifest)
    if {row["label"] for row in rows} != {"indic", "english"}:
        raise ValueError("Manifest must contain both indic and english labels")
    speaker_splits = {}
    for row in rows:
        previous = speaker_splits.setdefault(row["speaker_id"], row["split"])
        if previous != row["split"]:
            raise ValueError(f"Speaker leakage: {row['speaker_id']} is in {previous} and {row['split']}")

    extractor = FrozenECAPA(args.model_dir, args.device)
    cache = args.manifest.with_name("ecapa_embeddings.npz")
    x = embeddings(rows, extractor, args.device, cache, sha256(args.manifest))
    y = np.asarray([row["label"] == "english" for row in rows], dtype=np.int64)
    splits = np.asarray([row["split"] for row in rows])
    train, val, test = splits == "train", splits == "val", splits == "test"
    scaler = StandardScaler().fit(x[train])
    classifier = LogisticRegression(class_weight="balanced", max_iter=1_000, random_state=0)
    classifier.fit(scaler.transform(x[train]), y[train])
    val_probabilities = classifier.predict_proba(scaler.transform(x[val]))[:, 1]
    candidates = np.linspace(0.05, 0.95, 181)
    threshold = max(
        candidates,
        key=lambda value: (balanced_accuracy_score(y[val], val_probabilities >= value), -abs(value - 0.5)),
    )
    # Fold the selected threshold into the bias, leaving runtime's decision at p=0.5.
    threshold_logit = np.log(threshold / (1 - threshold))
    adjusted_bias = classifier.intercept_[0] - threshold_logit
    checkpoint = {
        "format_version": 1,
        "mean": torch.tensor(scaler.mean_, dtype=torch.float32, device=args.device),
        "scale": torch.tensor(scaler.scale_, dtype=torch.float32, device=args.device),
        "weight": torch.tensor(classifier.coef_[0], dtype=torch.float32, device=args.device),
        "bias": torch.tensor(adjusted_bias, dtype=torch.float32, device=args.device),
    }
    torch.save({key: value.cpu() if torch.is_tensor(value) else value for key, value in checkpoint.items()}, args.output)
    report = {
        "model": "speechbrain/lang-id-voxlingua107-ecapa frozen embedding + logistic head",
        "sources": ["google/fleurs", "ishands/commonvoice-indian_accent"],
        "training_samples": int(train.sum()),
        "validation": metrics(y[val], val_probabilities, threshold),
        "selected_raw_threshold": float(threshold),
        "test": metrics(
            y[test], classifier.predict_proba(scaler.transform(x[test]))[:, 1], threshold,
        ) if test.any() else None,
        "head_bytes": args.output.stat().st_size,
        "manifest_sha256": sha256(args.manifest),
    }
    report_path = args.output.with_suffix(".json")
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Saved head: {args.output.resolve()}")


if __name__ == "__main__":
    main()
