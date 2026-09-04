"""Backward-compatible launcher for ``python -m scripts.prepare_fleurs``."""

from scripts.prepare_fleurs import *  # noqa: F401,F403
from scripts.prepare_fleurs import main

if __name__ == "__main__":
    main()
