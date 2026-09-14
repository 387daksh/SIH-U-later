"""Generate the minimal audible FastPitch word-level pitch demo."""

import json
import sys
import unicodedata
import wave
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper

from verify_fastpitch_prosody_onnx import FastPitchProsody, median_f0, session


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "models/tts/en-hi"
OUTPUT_DIR = ROOT / "artifacts/pitch_demo"
SENTENCE = "पानी और दवा अभी भेजिए"
SYNTHESIS_TEXT = SENTENCE + "."
EMPHASIZED_WORD = "अभी"


def calibrated_bias(calibration, desired_hz):
    points = calibration["pooled"]["desired_hz_shift_to_bias_lookup"]
    shifts = np.array([point["desired_delta_f0_hz"] for point in points])
    if not shifts[0] <= desired_hz <= shifts[-1]:
        raise ValueError(f"{desired_hz} Hz is outside calibrated range {shifts[[0, -1]]}")
    return float(np.interp(desired_hz, shifts, [point["bias"] for point in points]))


def normalized_text(text, ids):
    value = unicodedata.normalize("NFC", text).lower()
    value = "".join(" " if char.isspace() else char for char in value)
    return " ".join("".join(char for char in value if char in ids).split())


def word_indices(text):
    result = {}
    cursor = 0
    for word in text.split(" "):
        result[word] = list(range(cursor, cursor + len(word)))
        cursor += len(word) + 1
    return result


def prosody_session(path):
    model = onnx.load(path)
    model.graph.output.append(
        helper.make_tensor_value_info(
            "/Round_1_output_0", TensorProto.FLOAT, ["B", "T_text"]
        )
    )
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(
        model.SerializeToString(), options, providers=["CPUExecutionProvider"]
    )


def write_wav(path, waveform, sample_rate):
    pcm = np.round(np.clip(waveform, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setparams((1, 2, sample_rate, len(pcm), "NONE", "not compressed"))
        output.writeframes(pcm.tobytes())
    return pcm.astype(np.float64) / 32767.0


def per_word_f0(waveform, words, durations, hop_length, sample_rate):
    cumulative = np.concatenate(([0], np.cumsum(durations.astype(int))))
    return {
        word: median_f0(
            waveform[cumulative[indices[0]] * hop_length : cumulative[indices[-1] + 1] * hop_length],
            sample_rate,
            hop_length,
        )
        for word, indices in words.items()
    }


def main():
    calibration_path = ROOT / "artifacts/pitch_calibration.json"
    calibration = json.loads(calibration_path.read_text(encoding="utf-8"))
    bias_15 = calibrated_bias(calibration, 15.0)
    bias_25 = calibrated_bias(calibration, 25.0)

    fastpitch_path = MODEL_DIR / "fastpitch_en+hi.prosody.onnx"
    synthesizer = FastPitchProsody(fastpitch_path, MODEL_DIR / "meta.json")
    runtime = prosody_session(fastpitch_path)
    vocoder = session(MODEL_DIR / "hifigan_en+hi.minmax.onnx")
    text = normalized_text(SYNTHESIS_TEXT, synthesizer.ids)
    tokens = synthesizer.tokens(SYNTHESIS_TEXT)
    assert tokens.tolist()[0] == [synthesizer.ids[char] for char in text]
    words = word_indices(normalized_text(SENTENCE, synthesizer.ids))
    target_indices = words[EMPHASIZED_WORD]
    shape = tokens.shape
    controls = {
        "baseline": np.zeros(shape, np.float32),
        "uniform_shift": np.full(shape, bias_15, np.float32),
        "emphasis": np.zeros(shape, np.float32),
    }
    controls["emphasis"][0, target_indices] = bias_25
    duration_scale = np.ones(shape, np.float32)
    common = {
        "tokens": tokens,
        "speaker_id": np.array([synthesizer.meta["speaker_id"]], np.int64),
        "length_scale": np.array([synthesizer.meta["length_scale_default"]], np.float32),
        "duration_scale": duration_scale,
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    sample_rate = synthesizer.meta["sample_rate"]
    hop_length = synthesizer.meta["hop_length"]
    measurements = {}
    baseline_mel = None
    durations = None
    for name, pitch_bias in controls.items():
        mel, case_durations = runtime.run(
            None, {**common, "pitch_bias": pitch_bias}
        )
        if durations is None:
            durations = case_durations.squeeze()
        else:
            assert np.array_equal(durations, case_durations.squeeze())
        waveform = np.asarray(vocoder.run(None, {"mel": mel})[0]).squeeze()
        saved_waveform = write_wav(OUTPUT_DIR / f"{name}.wav", waveform, sample_rate)
        word_f0 = per_word_f0(
            saved_waveform, words, durations, hop_length, sample_rate
        )
        measurements[name] = {
            "whole_sentence_f0_hz": median_f0(saved_waveform, sample_rate, hop_length),
            "per_word_f0_hz": word_f0,
            "peak_before_pcm_clipping": float(np.max(np.abs(waveform))),
        }
        if name == "baseline":
            baseline_mel = mel

    original = session(MODEL_DIR / "fastpitch_en+hi.qdq-safe.onnx")
    original_mel = original.run(
        None,
        {key: common[key] for key in ("tokens", "speaker_id", "length_scale")},
    )[0]
    neutral_max_abs = float(np.max(np.abs(original_mel - baseline_mel)))
    assert neutral_max_abs <= 1e-6
    emphasis_words = measurements["emphasis"]["per_word_f0_hz"]
    baseline_words = measurements["baseline"]["per_word_f0_hz"]
    other_f0 = [value for word, value in emphasis_words.items() if word != EMPHASIZED_WORD]
    other_shifts = [
        emphasis_words[word] - baseline_words[word]
        for word in emphasis_words
        if word != EMPHASIZED_WORD
    ]
    proof = {
        "target_word_f0_hz": emphasis_words[EMPHASIZED_WORD],
        "target_word_baseline_f0_hz": baseline_words[EMPHASIZED_WORD],
        "target_word_shift_from_baseline_hz": (
            emphasis_words[EMPHASIZED_WORD] - baseline_words[EMPHASIZED_WORD]
        ),
        "other_words_median_f0_hz": float(np.median(other_f0)),
        "gap_hz": emphasis_words[EMPHASIZED_WORD] - float(np.median(other_f0)),
        "other_words_max_abs_shift_from_baseline_hz": float(
            np.max(np.abs(other_shifts))
        ),
        "uniform_whole_sentence_shift_from_baseline_hz": (
            measurements["uniform_shift"]["whole_sentence_f0_hz"]
            - measurements["baseline"]["whole_sentence_f0_hz"]
        ),
    }
    report = {
        "sentence": SENTENCE,
        "synthesis_text": SYNTHESIS_TEXT,
        "emphasized_word": EMPHASIZED_WORD,
        "sentence_choice": "Five-word extension of the calibrated water-and-medicine request; emphasizing 'अभी' naturally conveys urgency.",
        "terminal_pause": "A supported period token supplies the model's natural phrase ending; Hindi danda is not in this graph's vocabulary.",
        "sample_rate_hz": sample_rate,
        "tokenizer": "IndicOnnxTts.tokens() character vocabulary and NFC/space normalization, mirrored by FastPitchProsody.tokens()",
        "normalized_text": text,
        "token_ids": tokens.tolist()[0],
        "word_token_indices": words,
        "emphasized_token_indices": target_indices,
        "calibration": {
            "source": str(calibration_path.relative_to(ROOT)),
            "method": calibration["pooled"]["interpolation"],
            "valid_delta_f0_hz_range": calibration["pooled"]["valid_delta_f0_hz_range"],
            "uniform_target_hz": 15.0,
            "uniform_bias": bias_15,
            "emphasis_target_hz": 25.0,
            "emphasis_bias": bias_25,
        },
        "pitch_bias_arrays": {name: values.tolist()[0] for name, values in controls.items()},
        "duration_scale": duration_scale.tolist()[0],
        "token_durations_mel_frames": durations.tolist(),
        "neutral_graph_max_abs_mel_difference": neutral_max_abs,
        "measurements": measurements,
        "emphasis_proof": proof,
    }
    report_path = OUTPUT_DIR / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    print(report_path.resolve(), flush=True)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
