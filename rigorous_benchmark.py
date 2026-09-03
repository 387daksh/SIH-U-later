"""Backward-compatible launcher for ``python -m scripts.benchmark``."""

from scripts.benchmark import *  # noqa: F401,F403
from scripts.benchmark import main

if __name__ == "__main__":
    main()
