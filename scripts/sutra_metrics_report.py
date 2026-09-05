#!/usr/bin/env python3
"""Summarize exported SUTRA sender-side latency events without third-party packages."""

import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(probability * len(ordered)) - 1)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path", type=Path)
    args = parser.parse_args()
    groups: dict[str, list[float]] = defaultdict(list)
    with args.csv_path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            if "sender_elapsed_ms=" not in row["detail"]:
                continue
            value = float(row["detail"].split("sender_elapsed_ms=", 1)[1].split(",", 1)[0])
            groups[row["event"]].append(value)
    if not groups:
        raise SystemExit("No sender_elapsed_ms events found")
    print("event,count,p50_ms,p95_ms,max_ms")
    for event, values in sorted(groups.items()):
        print(f"{event},{len(values)},{percentile(values, .50):.1f},{percentile(values, .95):.1f},{max(values):.1f}")


if __name__ == "__main__":
    main()
