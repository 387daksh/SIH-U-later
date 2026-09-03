"""Backward-compatible entry point; new code should import :mod:`sih_speech`."""

from sih_speech.pipeline import *  # noqa: F401,F403
from sih_speech.pipeline import main

if __name__ == "__main__":
    main()
