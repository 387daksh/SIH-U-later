"""Regression checks for benchmark metric accounting."""

from scripts.benchmark import edits, normalize, text_metrics
from scripts.prepare_fleurs import choose_rows

assert normalize("Hello,  WORLD!") == "hello world"
assert normalize("नमस्ते।") == "नमस्ते"
assert edits(["a", "b", "c"], ["a", "x", "c", "d"]) == (2, 1, 0, 1)
metrics = text_metrics("one two three", "one four")
assert metrics["word_errors"] == 2
assert metrics["word_substitutions"] == 1
assert metrics["word_deletions"] == 1
assert metrics["reference_words"] == 3
sample_rows = [
    ["1", f"{index}.wav", "text", "raw", "16000", gender]
    for index, gender in enumerate(["MALE", "MALE", "FEMALE", "FEMALE"])
]
assert len(choose_rows(sample_rows, 4, 1, "test")) == 4

print("PASS: Unicode normalization and WER/CER edit accounting are correct.")
