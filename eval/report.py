"""
report.py — Reporting layer for the LLM-augmented pointer-analysis evaluation.

Usage
-----
    python report.py <results.csv> <robustness.csv> <outdir>

Produces in <outdir>:
    comparison_table.md         — markdown table, one row per config
    pareto_precision_vs_time.png — avgPtsSize (y, lower=better) vs timeMs (x)
    robustness_recall_vs_error.png — recall (y) vs errorRate (x), one line/arm

CSV schemas consumed
--------------------
results.csv  (MetricCollector.CSV_HEADER):
    config,benchmark,timeMs,memMb,
    mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects

robustness.csv  (derived from RobustnessSweep.SweepPoint):
    config,errorRate,recall,
    mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects
"""

import argparse
import os
import pathlib
import sys

import matplotlib
matplotlib.use("Agg")  # non-interactive backend — must be set before pyplot import
import matplotlib.pyplot as plt
import pandas as pd

# ---------------------------------------------------------------------------
# Config display order (canonical order for the comparison table)
# ---------------------------------------------------------------------------
CONFIG_ORDER = ["B0", "B1", "B2z", "B2s", "B3", "A1", "A2", "A3"]

# Columns shown in the comparison table (in order)
TABLE_COLS = [
    "config",
    "benchmark",
    "timeMs",
    "memMb",
    "mayFailCasts",
    "avgPtsSize",
    "polyCallSites",
    "reachableMethods",
    "aliasPairs",
    "objects",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _sort_configs(df: pd.DataFrame) -> pd.DataFrame:
    """Sort rows by CONFIG_ORDER; unknown configs appended at the end."""
    order_map = {c: i for i, c in enumerate(CONFIG_ORDER)}
    df = df.copy()
    df["_sort_key"] = df["config"].map(lambda c: order_map.get(c, len(CONFIG_ORDER)))
    df = df.sort_values("_sort_key").drop(columns=["_sort_key"])
    return df.reset_index(drop=True)


# ---------------------------------------------------------------------------
# Step 1: comparison table
# ---------------------------------------------------------------------------

def _make_comparison_table(results: pd.DataFrame, outdir: pathlib.Path) -> None:
    """Write comparison_table.md to *outdir*."""
    # Aggregate over benchmarks: mean numeric cols, keep config as group key.
    numeric_cols = [
        "timeMs", "memMb", "mayFailCasts", "avgPtsSize",
        "polyCallSites", "reachableMethods", "aliasPairs", "objects",
    ]
    agg = (
        results.groupby("config")[numeric_cols]
        .mean()
        .reset_index()
    )

    # Also keep the benchmark name (comma-joined if multiple)
    bench_map = results.groupby("config")["benchmark"].apply(
        lambda s: ",".join(sorted(s.unique()))
    )
    agg["benchmark"] = agg["config"].map(bench_map)

    # Sort into canonical display order
    agg = _sort_configs(agg)

    # Select and reorder columns for the table
    cols_present = [c for c in TABLE_COLS if c in agg.columns]
    agg = agg[cols_present]

    # Build markdown
    lines = []

    # Header
    header = "| " + " | ".join(cols_present) + " |"
    separator = "| " + " | ".join("---" for _ in cols_present) + " |"
    lines.append(header)
    lines.append(separator)

    # Data rows
    for _, row in agg.iterrows():
        cells = []
        for col in cols_present:
            val = row[col]
            if isinstance(val, float):
                cells.append(f"{val:.4f}")
            else:
                cells.append(str(val))
        lines.append("| " + " | ".join(cells) + " |")

    md = "\n".join(lines) + "\n"
    (outdir / "comparison_table.md").write_text(md, encoding="utf-8")


# ---------------------------------------------------------------------------
# Step 2: Pareto — precision vs time
# ---------------------------------------------------------------------------

def _make_pareto_plot(results: pd.DataFrame, outdir: pathlib.Path) -> None:
    """
    Scatter plot: x = mean timeMs (cost), y = mean avgPtsSize (precision, lower=better).
    One point per config, labeled. A line connects the Pareto-optimal front.
    """
    numeric_cols = ["timeMs", "avgPtsSize", "mayFailCasts"]
    agg = results.groupby("config")[numeric_cols].mean().reset_index()
    agg = _sort_configs(agg)

    fig, ax = plt.subplots(figsize=(8, 5))

    xs = agg["timeMs"].tolist()
    ys = agg["avgPtsSize"].tolist()
    labels = agg["config"].tolist()

    ax.scatter(xs, ys, s=80, zorder=3, color="steelblue")

    for x, y, lbl in zip(xs, ys, labels):
        ax.annotate(lbl, (x, y), textcoords="offset points",
                    xytext=(5, 4), fontsize=9)

    # Compute and draw the Pareto frontier (lower avgPtsSize AND lower timeMs = better)
    # Sort by timeMs; carry forward min avgPtsSize seen so far
    sorted_pts = sorted(zip(xs, ys, labels), key=lambda t: t[0])
    pareto_xs, pareto_ys = [], []
    min_y = float("inf")
    for px, py, _ in sorted_pts:
        if py < min_y:
            pareto_xs.append(px)
            pareto_ys.append(py)
            min_y = py
    if len(pareto_xs) > 1:
        ax.plot(pareto_xs, pareto_ys, "r--", linewidth=1.2,
                label="Pareto frontier", zorder=2)
        ax.legend(fontsize=9)

    ax.set_xlabel("Analysis time (ms)", fontsize=11)
    ax.set_ylabel("Avg points-to set size (lower = more precise)", fontsize=11)
    ax.set_title("Precision vs. Analysis Cost (Pareto view)", fontsize=12)
    ax.grid(True, linestyle="--", alpha=0.4)
    fig.tight_layout()
    fig.savefig(outdir / "pareto_precision_vs_time.png", dpi=150)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Step 3: Robustness — recall vs error rate
# ---------------------------------------------------------------------------

def _make_robustness_plot(robustness: pd.DataFrame, outdir: pathlib.Path) -> None:
    """
    Line plot: x = errorRate, y = recall, one line per config/arm.
    Sound arms remain flat at 1.0; unsound (CAFD-style) arm declines.
    """
    configs = robustness["config"].unique()

    # Determine line style per arm: CAFD-style unsound gets a dashed red line
    def _style(cfg: str):
        if "cafd" in cfg.lower() or "unsound" in cfg.lower():
            return {"linestyle": "--", "color": "red", "marker": "o", "linewidth": 2}
        return {"linestyle": "-", "marker": "s", "linewidth": 1.5}

    fig, ax = plt.subplots(figsize=(7, 5))

    for cfg in sorted(configs):
        sub = robustness[robustness["config"] == cfg].sort_values("errorRate")
        ax.plot(sub["errorRate"], sub["recall"], label=cfg, **_style(cfg))

    ax.set_xlabel("LLM error rate", fontsize=11)
    ax.set_ylabel("Recall (fraction of ground-truth call-graph edges retained)",
                  fontsize=10)
    ax.set_title("Robustness under Oracle Corruption", fontsize=12)
    ax.set_xlim(-0.02, 1.02)
    ax.set_ylim(-0.02, 1.10)
    ax.axhline(1.0, color="grey", linestyle=":", linewidth=0.8, alpha=0.6)
    ax.legend(fontsize=9, loc="lower left")
    ax.grid(True, linestyle="--", alpha=0.4)
    fig.tight_layout()
    fig.savefig(outdir / "robustness_recall_vs_error.png", dpi=150)
    plt.close(fig)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def generate_report(results_csv: str, robustness_csv: str, outdir: str) -> None:
    """
    Main entry point — can also be called programmatically.

    Parameters
    ----------
    results_csv:    path to results CSV (MetricCollector schema)
    robustness_csv: path to robustness CSV (RobustnessSweep schema)
    outdir:         directory where outputs are written (created if absent)
    """
    out = pathlib.Path(outdir)
    out.mkdir(parents=True, exist_ok=True)

    results = pd.read_csv(results_csv)
    robustness = pd.read_csv(robustness_csv)

    _make_comparison_table(results, out)
    _make_pareto_plot(results, out)
    _make_robustness_plot(robustness, out)

    print(f"[report] outputs written to {out.resolve()}")
    print(f"  comparison_table.md")
    print(f"  pareto_precision_vs_time.png")
    print(f"  robustness_recall_vs_error.png")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate comparison table + Pareto/robustness plots from eval CSVs."
    )
    parser.add_argument("results_csv",
                        help="Path to results.csv (MetricCollector schema)")
    parser.add_argument("robustness_csv",
                        help="Path to robustness.csv (RobustnessSweep schema)")
    parser.add_argument("outdir",
                        help="Output directory (created if absent)")
    args = parser.parse_args(argv)

    generate_report(args.results_csv, args.robustness_csv, args.outdir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
