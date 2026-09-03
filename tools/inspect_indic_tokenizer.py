"""Check whether IndicConformer's vocabularies can represent Latin text."""

import json
from pathlib import Path

from huggingface_hub import hf_hub_download

MODEL_NAME = "ai4bharat/indic-conformer-600m-multilingual"
TEST_STRINGS = [
    "help me please",
    "I need medical assistance immediately",
    "hospital emergency ambulance",
    "yaar mujhe help chahiye",
    "please send water and supplies",
]


def contains_latin(token):
    return any("a" <= char.lower() <= "z" for char in token)


print("Downloading vocabulary (the acoustic model is not needed for this check)...")
vocab_path = hf_hub_download(MODEL_NAME, "assets/vocab.json")
vocabularies = json.loads(Path(vocab_path).read_text(encoding="utf-8"))
latin_tokens = {
    language: [token for token in vocabulary if token != "<unk>" and contains_latin(token)]
    for language, vocabulary in vocabularies.items()
}

assert vocabularies and all("<unk>" in vocabulary for vocabulary in vocabularies.values())

print(f"Languages: {len(vocabularies)}")
print(f"Vocabulary size per language: {sorted({len(v) for v in vocabularies.values()})}")
print(f"Latin-script tokens across all vocabularies: {sum(map(len, latin_tokens.values()))}")

print("\n" + "=" * 70)
print("ENGLISH REPRESENTABILITY TEST")
print("=" * 70)
for text in TEST_STRINGS:
    unsupported_words = [word for word in text.split() if contains_latin(word)]
    print(f"\n  Input: '{text}'")
    print(f"  Unsupported Latin words: {unsupported_words}")
    print(f"  Result: {len(unsupported_words)} <unk> token(s)")

print("\n" + "=" * 70)
if any(latin_tokens.values()):
    print("RESULT: Some Latin coverage exists; inspect the listed tokens before Step 2.")
    print({language: tokens for language, tokens in latin_tokens.items() if tokens})
else:
    print("RESULT: No English/Latin vocabulary coverage.")
    print("LoRA alone cannot add English output tokens; use an English ASR model or extend the vocabulary.")
print("=" * 70)
