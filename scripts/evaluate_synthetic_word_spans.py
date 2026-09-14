"""Generate FastPitch timing truth and compare it with IndicConformer CTC spans."""

import argparse
import json
import os
from pathlib import Path
import tempfile
import unicodedata

import numpy as np
import onnx
from onnx import TensorProto, helper
import onnxruntime as ort
import soundfile as sf

from sih_speech.pipeline import IndicASR, load_audio


SENTENCES = [
    "मदद कीजिए",
    "पानी और दवा भेजिए",
    "मैं ठीक हूँ",
    "एम्बुलेंस तुरंत यहाँ भेजिए",
    "रास्ता बंद है धीरे आइए",
    "स्कूल के पास नाव भेजिए",
    "पाँच लोग यहाँ सुरक्षित हैं",
]
RAW_DURATION = "/Clip_output_0"
QDQ_DURATION = "/Clip_output_0_DequantizeLinear_Output"
ALIGNMENT = "/Cast_10_output_0"


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def debug_fastpitch(path):
    model = onnx.load(path)
    model.graph.output.extend(
        [
            helper.make_tensor_value_info(RAW_DURATION, TensorProto.FLOAT, ["B", "T_text"]),
            helper.make_tensor_value_info(QDQ_DURATION, TensorProto.FLOAT, ["B", "T_text"]),
            helper.make_tensor_value_info(ALIGNMENT, TensorProto.FLOAT, ["B", "T_text", "T_mel"]),
        ]
    )
    descriptor, name = tempfile.mkstemp(suffix=".onnx")
    os.close(descriptor)
    onnx.save(model, name)
    try:
        return session(name)
    finally:
        os.unlink(name)


class CharacterTokenizer:
    def __init__(self, vocab):
        self.ids = {token: index for index, token in enumerate(vocab) if len(token) == 1}

    def encode(self, text):
        text = unicodedata.normalize("NFC", text).lower()
        text = "".join(" " if char.isspace() else char for char in text)
        text = " ".join("".join(char for char in text if char in self.ids).split())
        return text, np.array([[self.ids[char] for char in text]], dtype=np.int64)


def words_from_alignment(text, alignment, seconds_per_frame):
    words, current = [], None
    for char, token_alignment in zip(text, alignment):
        occupied = np.flatnonzero(token_alignment > 0.5)
        if char == " ":
            if current:
                words.append(current)
                current = None
            continue
        start = int(occupied[0]) if len(occupied) else None
        end = int(occupied[-1]) + 1 if len(occupied) else None
        if current is None:
            current = {
                "text": char,
                "start_sec": start * seconds_per_frame if start is not None else None,
                "end_sec": end * seconds_per_frame if end is not None else None,
            }
        else:
            current["text"] += char
            if current["start_sec"] is None and start is not None:
                current["start_sec"] = start * seconds_per_frame
            if end is not None:
                current["end_sec"] = end * seconds_per_frame
    if current:
        words.append(current)
    return words


def compare_words(truth, extracted):
    rows = []
    for index in range(max(len(truth), len(extracted))):
        expected = truth[index] if index < len(truth) else None
        actual = extracted[index] if index < len(extracted) else None
        rows.append(
            {
                "index": index,
                "truth": expected["text"] if expected else None,
                "stt": actual["text"] if actual else None,
                "text_match": bool(expected and actual and expected["text"] == actual["text"]),
                "start_error_ms": (
                    (actual["start_sec"] - expected["start_sec"]) * 1000
                    if expected and actual and expected["start_sec"] is not None else None
                ),
                "end_error_ms": (
                    (actual["end_sec"] - expected["end_sec"]) * 1000
                    if expected and actual and expected["end_sec"] is not None else None
                ),
            }
        )
    paired = [row for row in rows if row["start_error_ms"] is not None]
    if len(paired) > 1:
        indices = np.arange(len(paired), dtype=np.float64)
        start_slope = float(np.polyfit(indices, [row["start_error_ms"] for row in paired], 1)[0])
        end_slope = float(np.polyfit(indices, [row["end_error_ms"] for row in paired], 1)[0])
    else:
        start_slope = end_slope = None
    return rows, start_slope, end_slope


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tts-dir", type=Path, default=Path("models/tts/en-hi"))
    parser.add_argument(
        "--indic-dir", type=Path, default=Path("deployment_models/indic/hybrid_int8")
    )
    parser.add_argument(
        "--output", type=Path, default=Path("artifacts/synthetic_word_spans")
    )
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    meta = json.loads((args.tts_dir / "meta.json").read_text(encoding="utf-8"))
    tokenizer = CharacterTokenizer(meta["vocab"])
    fastpitch = debug_fastpitch(args.tts_dir / "fastpitch_en+hi.qdq-safe.onnx")
    hifigan = session(args.tts_dir / "hifigan_en+hi.minmax.onnx")
    asr = IndicASR(args.indic_dir)
    results = []
    try:
        for index, sentence in enumerate(SENTENCES, 1):
            clean, tokens = tokenizer.encode(sentence)
            feeds = {
                "tokens": tokens,
                "speaker_id": np.array([meta["speaker_id"]], dtype=np.int64),
                "length_scale": np.array([meta["length_scale_default"]], dtype=np.float32),
            }
            mel, raw, qdq, alignment = fastpitch.run(
                ["mel", RAW_DURATION, QDQ_DURATION, ALIGNMENT], feeds
            )
            raw = np.rint(raw[0]).astype(np.int64)
            effective = np.rint(alignment[0].sum(axis=1)).astype(np.int64)
            if len(clean) != len(raw) or len(raw) != len(effective):
                raise RuntimeError("Tokenizer and duration lengths disagree")
            if not np.allclose(alignment, np.rint(alignment), atol=1e-6):
                raise RuntimeError("Length-regulator alignment is not binary")
            column_assignments = alignment[0].sum(axis=0)
            waveform = np.asarray(hifigan.run(None, {"mel": mel})[0]).squeeze()
            stem = f"{index:02d}"
            wav_22050 = args.output / f"{stem}_22050.wav"
            wav_16000 = args.output / f"{stem}_16000.wav"
            sf.write(wav_22050, waveform, meta["sample_rate"], subtype="PCM_16")
            resampled = load_audio(wav_22050).numpy()
            sf.write(wav_16000, resampled, 16_000, subtype="PCM_16")

            truth = words_from_alignment(
                clean, alignment[0], meta["hop_length"] / meta["sample_rate"]
            )
            all_languages = asr.transcribe_all(wav_16000)
            hindi = next(
                item for item in all_languages["candidates"] if item["language"] == "hindi"
            )
            comparison, start_slope, end_slope = compare_words(truth, hindi["words"])
            result = {
                "sentence": sentence,
                "normalized_text": clean,
                "wav_22050": wav_22050.name,
                "wav_16000": wav_16000.name,
                "token_ids": tokens[0].tolist(),
                "raw_post_clip_durations": raw.tolist(),
                "qdq_durations": qdq[0].tolist(),
                "effective_alignment_durations": effective.tolist(),
                "mel_frames": int(mel.shape[-1]),
                "unassigned_mel_frames": int(np.count_nonzero(column_assignments == 0)),
                "overlapping_mel_frames": int(np.count_nonzero(column_assignments > 1)),
                "raw_matches_effective": bool(np.array_equal(raw, effective)),
                "words": truth,
                "stt_winner": all_languages["language"],
                "stt_hindi_text": hindi["text"],
                "stt_words": hindi["words"],
                "comparison": comparison,
                "start_error_slope_ms_per_word": start_slope,
                "end_error_slope_ms_per_word": end_slope,
            }
            results.append(result)
            print(json.dumps(result, ensure_ascii=False))
    finally:
        for model in asr.model.models.values():
            close = getattr(model, "close", None)
            if close:
                close()

    report = {
        "ground_truth": "FastPitch effective alignment token-column positions",
        "tts_frame_seconds": meta["hop_length"] / meta["sample_rate"],
        "stt_frame_seconds": 0.08,
        "resampler": "sih_speech.pipeline.load_audio/scipy.signal.resample_poly",
        "sentences": results,
    }
    report_path = args.output / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
