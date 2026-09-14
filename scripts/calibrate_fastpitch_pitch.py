"""Measure the FastPitch prosody graph's predictor-bias to output-F0 mapping."""

import argparse
import json
from pathlib import Path

import numpy as np

from verify_fastpitch_prosody_onnx import FastPitchProsody, median_f0, session


SENTENCES = [
    "मदद कीजिए",
    "पानी और दवा भेजिए",
    "मैं ठीक हूँ",
    "पाँच लोग यहाँ सुरक्षित हैं",
]
DEFAULT_BIASES = [-0.3, -0.2, -0.1, -0.05, 0.0, 0.05, 0.1, 0.2, 0.3, 0.4]


def rms(waveform):
    waveform = np.asarray(waveform, dtype=np.float64)
    return float(np.sqrt(np.mean(waveform * waveform)))


def isotonic(values, weights=None):
    """Small pool-adjacent-violators fit for a nondecreasing sequence."""
    weights = [1.0] * len(values) if weights is None else list(weights)
    blocks = []
    for index, (value, weight) in enumerate(zip(values, weights)):
        blocks.append([index, index + 1, float(value), float(weight)])
        while len(blocks) > 1 and blocks[-2][2] > blocks[-1][2]:
            right = blocks.pop()
            left = blocks.pop()
            weight = left[3] + right[3]
            blocks.append([
                left[0], right[1],
                (left[2] * left[3] + right[2] * right[3]) / weight,
                weight,
            ])
    fitted = [0.0] * len(values)
    for start, end, value, _ in blocks:
        fitted[start:end] = [value] * (end - start)
    return fitted


def fit_points(observations):
    usable = [row for row in observations if row["delta_f0_hz"] is not None]
    fitted = isotonic([row["delta_f0_hz"] for row in usable])
    return [
        {"bias": row["bias"], "delta_f0_hz": value}
        for row, value in zip(usable, fitted)
    ]


def inverse_points(points):
    groups = []
    for point in points:
        if groups and abs(groups[-1][0] - point["delta_f0_hz"]) < 1e-9:
            groups[-1][1].append(point["bias"])
        else:
            groups.append([point["delta_f0_hz"], [point["bias"]]])
    return [
        {"desired_delta_f0_hz": delta, "bias": float(np.mean(biases))}
        for delta, biases in groups
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prosody", type=Path, default=Path("models/tts/en-hi/fastpitch_en+hi.prosody.onnx"))
    parser.add_argument("--hifigan", type=Path, default=Path("models/tts/en-hi/hifigan_en+hi.minmax.onnx"))
    parser.add_argument("--meta", type=Path, default=Path("models/tts/en-hi/meta.json"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/pitch_calibration.json"))
    args = parser.parse_args()

    synthesizer = FastPitchProsody(args.prosody, args.meta)
    vocoder = session(args.hifigan)
    sample_rate = synthesizer.meta["sample_rate"]
    hop_length = synthesizer.meta["hop_length"]
    sentence_results = []

    for text in SENTENCES:
        tokens = synthesizer.tokens(text)

        def synthesize(bias):
            mel = synthesizer.run(
                text, pitch_bias=np.full(tokens.shape, bias, dtype=np.float32)
            )
            return np.asarray(vocoder.run(None, {"mel": mel})[0]).squeeze()

        baseline_waveform = synthesize(0.0)
        baseline_rms = rms(baseline_waveform)
        baseline_f0 = median_f0(baseline_waveform, sample_rate, hop_length)
        observations = []
        for bias in DEFAULT_BIASES:
            waveform = synthesize(bias)
            value_rms = rms(waveform)
            collapsed = value_rms < 0.5 * baseline_rms
            value_f0 = median_f0(waveform, sample_rate, hop_length)
            usable = not collapsed and np.isfinite(value_f0)
            observations.append({
                "bias": bias,
                "rms": value_rms,
                "rms_ratio": value_rms / baseline_rms,
                "collapsed": collapsed,
                "f0_hz": value_f0 if usable else None,
                "delta_f0_hz": value_f0 - baseline_f0 if usable else None,
            })
        repeat_waveform = synthesize(0.0)
        repeat_rms = rms(repeat_waveform)
        repeat_f0 = median_f0(repeat_waveform, sample_rate, hop_length)
        negative_collapses = [row["bias"] for row in observations if row["bias"] < 0 and row["collapsed"]]
        positive_collapses = [row["bias"] for row in observations if row["bias"] > 0 and row["collapsed"]]
        result = {
            "text": text,
            "token_count": int(tokens.shape[1]),
            "baseline_f0_hz": baseline_f0,
            "baseline_rms": baseline_rms,
            "baseline_repeat_f0_hz": repeat_f0,
            "baseline_repeat_rms": repeat_rms,
            "baseline_f0_drift_hz": repeat_f0 - baseline_f0,
            "baseline_rms_drift": repeat_rms - baseline_rms,
            "collapse_threshold_observed": {
                "negative_bias": max(negative_collapses) if negative_collapses else None,
                "positive_bias": min(positive_collapses) if positive_collapses else None,
            },
            "observations": observations,
            "monotonic_fit": fit_points(observations),
        }
        sentence_results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    pooled_raw = []
    for bias in DEFAULT_BIASES:
        values = [
            row["delta_f0_hz"]
            for sentence in sentence_results
            for row in sentence["observations"]
            if row["bias"] == bias and row["delta_f0_hz"] is not None
        ]
        if values:
            pooled_raw.append({
                "bias": bias,
                "sentence_count": len(values),
                "median_delta_f0_hz": float(np.median(values)),
                "mean_delta_f0_hz": float(np.mean(values)),
                "std_delta_f0_hz": float(np.std(values)),
                "min_delta_f0_hz": min(values),
                "max_delta_f0_hz": max(values),
                "spread_delta_f0_hz": max(values) - min(values),
            })
    pooled_fit_values = isotonic(
        [row["median_delta_f0_hz"] for row in pooled_raw],
        [row["sentence_count"] for row in pooled_raw],
    )
    pooled_fit = [
        {"bias": row["bias"], "delta_f0_hz": fit}
        for row, fit in zip(pooled_raw, pooled_fit_values)
    ]
    assert all(
        left["delta_f0_hz"] <= right["delta_f0_hz"]
        for left, right in zip(pooled_fit, pooled_fit[1:])
    )
    all_sentence_biases = [
        row["bias"] for row in pooled_raw if row["sentence_count"] == len(SENTENCES)
    ]
    inverse = inverse_points(pooled_fit)
    report = {
        "schema_version": 1,
        "models": {"fastpitch": str(args.prosody), "vocoder": str(args.hifigan)},
        "f0_estimator": {
            "method": "median voiced-frame autocorrelation",
            "frame_samples": 2048,
            "hop_samples": hop_length,
            "search_range_hz": [50, 500],
            "frame_rms_floor": 0.005,
        },
        "collapse_definition": "waveform RMS below 50% of that sentence's zero-bias baseline",
        "biases_tested": DEFAULT_BIASES,
        "sentences": sentence_results,
        "pooled": {
            "raw_by_bias": pooled_raw,
            "monotonic_fit": pooled_fit,
            "desired_hz_shift_to_bias_lookup": inverse,
            "interpolation": "piecewise linear; interpolate desired_delta_f0_hz to bias",
            "valid_bias_range_all_sentences": [min(all_sentence_biases), max(all_sentence_biases)],
            "valid_delta_f0_hz_range": [inverse[0]["desired_delta_f0_hz"], inverse[-1]["desired_delta_f0_hz"]],
            "outside_range": "not calibrated; do not extrapolate because pitch tracking and audio stability may fail",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(args.output.resolve())


if __name__ == "__main__":
    main()
