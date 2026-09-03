"""Routing and CTC-confidence regression checks; no downloads required."""

from types import SimpleNamespace

import torch

from sih_speech import (
    INDIC_ID, LANGUAGES, WHISPER_ID, IndicASR, SpeechPipeline,
    restrict_log_probabilities,
)


class FakeLID:
    def __init__(self, language):
        self.language = language

    def predict(self, _):
        return {
            "coarse_group": "english" if self.language == "english" else "indic",
            "legacy_language": "bengali" if self.language == "odia" else self.language,
            "confidence": 1.0,
            "scores": {"english": 1.0},
            "latency_ms": 1.0,
        }


class FakeASR:
    def __init__(self, text):
        self.text = text
        self.calls = []

    def transcribe(self, *args):
        self.calls.append(args)
        return self.text

    def transcribe_all(self, *args):
        self.calls.append(args)
        return {
            "language": self.text,
            "text": "indic transcript",
            "model": INDIC_ID,
            "indic_total_ms": 2.0,
            "candidates": [],
        }


log_scores = torch.tensor([-0.758, -0.791, -6.7])
restricted = restrict_log_probabilities(log_scores, torch.tensor([0, 1, 2]))
assert restricted.argmax().item() == 0 and torch.isclose(restricted.sum(), torch.tensor(1.0))

for language in LANGUAGES:
    pipeline = SpeechPipeline.__new__(SpeechPipeline)
    pipeline.lid = FakeLID(language)
    pipeline.indic = FakeASR(language)
    pipeline.whisper = FakeASR("english")
    result = pipeline.transcribe("sample.wav")
    if language == "english":
        assert result["model"] == WHISPER_ID and not pipeline.indic.calls
    else:
        assert result["model"] == INDIC_ID and not pipeline.whisper.calls
    assert result["lid_scores"] == {"english": 1.0}

decoder = IndicASR.__new__(IndicASR)
decoder.model = SimpleNamespace(
    language_masks={"hi": [True, True, True, False]},
    vocab={"hi": ["▁a", "b", ""]},
    config=SimpleNamespace(BLANK_ID=2),
)
logits = torch.tensor([[8.0, 0.0, 0.0, 9.0], [0.0, 8.0, 0.0, 1.0], [0.0, 0.0, 8.0, 1.0]])
decoded = decoder._decode_language(logits, logits.log_softmax(-1), "hindi")
assert decoded["text"] == "ab" and decoded["token_count"] == 2

print("PASS: coarse English routing and reusable nine-way Indic result are correct.")
