#!/usr/bin/env python3
"""Plot accepted process RSS and detached-GC checkpoints (requires matplotlib)."""
import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

from summarize_baseline_memory import timestamp, validate


def plot(directory, output):
    if output.exists():
        raise FileExistsError(output)
    _, scopes, process = validate(directory)
    origin = timestamp(process[0]["time"])
    elapsed = [(timestamp(r["time"]) - origin) / 60 for r in process]
    rss = [int(r["rss_bytes"]) / 1e6 for r in process]
    checkpoints = [s for s in scopes if s["scope"] == "detached_gc_checkpoint"]
    times = [(timestamp(s["end"]) - origin) / 60 for s in checkpoints]
    heaps = [int(s["heap_after"]) / 1e6 for s in checkpoints]
    with plt.rc_context({"font.family": "DejaVu Sans", "font.size": 11, "axes.spines.top": False,
                         "axes.spines.right": False, "axes.titlelocation": "left"}):
        figure, axes = plt.subplots(2, 1, figsize=(11, 6.5), sharex=True, layout="constrained")
        figure.suptitle("Sustained navigation: process memory and detached heap", fontsize=16, x=.04, ha="left")
        axes[0].plot(elapsed, rss, color="#176b9b", linewidth=1.4)
        axes[0].set_ylabel("Process RSS (MB)")
        axes[0].set_ylim(bottom=0)
        axes[0].set_title("Includes source preparation, production navigation and exact CPU controls", fontsize=10)
        axes[1].plot(times, heaps, "o--", color="#b65b12", linewidth=1.2)
        for t, heap in zip(times, heaps):
            axes[1].annotate(f"{heap:.1f} MB", (t, heap), xytext=(0, 12), textcoords="offset points", ha="center")
        axes[1].set_ylabel("Heap after detached GC (MB)")
        axes[1].set_ylim(0, max(heaps) * 1.5)
        axes[1].set_xlabel("Elapsed time (minutes)")
        axes[1].set_title("One explicit GC checkpoint per complete round; different scale from RSS", fontsize=10)
        for ax in axes:
            ax.grid(axis="y", alpha=.2)
        figure.savefig(output)
        plt.close(figure)
        if output.suffix.lower() == ".svg":
            output.write_text("\n".join(line.rstrip() for line in output.read_text().splitlines()) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    plot(args.directory, args.output)
