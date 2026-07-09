#!/usr/bin/env python3
"""RQ-adv: recall/precision/pollution vs corruption rate, per mode.
Self-checking: asserts the theorem-level claims the paper will make.

Reads Task-4 reach-set dumps from ../raw/reachdumps/ (tag "clean" for the
baseline pass, "<mode>-<rate>-s<seed>" for corrupt points), writes
../raw/adversarial.csv and the paper figure Java_PTA-FSE27/imgs/RQ_adv.pdf.

Env overrides (for validation runs; defaults are the paper matrix):
  BENCHES  comma list (default luindex,antlr,hsqldb,fop)
  MODES    comma list (default random,loadable,silent)
  RATES    comma list of corruption rates (default 0.25,0.50,0.75,1.00)
Missing corrupt points -> WARN + skip; missing baseline dumps for a
requested bench -> hard error.
"""
import csv
import os
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

DUMP = Path(__file__).resolve().parent.parent / "raw" / "reachdumps"
OUT_CSV = Path(__file__).resolve().parent.parent / "raw" / "adversarial.csv"
# scripts -> arm2 -> eval -> Tai-e -> PTA-enhancement (4 levels above scripts/),
# then into the paper repo's imgs/ (must already exist; RQ1_recall.pdf lives there).
FIG = (Path(__file__).resolve().parent.parent.parent.parent.parent
       / "Java_PTA-FSE27" / "imgs" / "RQ_adv.pdf")
BENCHES = os.environ.get("BENCHES", "luindex,antlr,hsqldb,fop").split(",")
MODES = os.environ.get("MODES", "random,loadable,silent").split(",")
RATES = [float(r) for r in
         os.environ.get("RATES", "0.25,0.50,0.75,1.00").split(",")]

if not FIG.parent.is_dir():
    sys.exit(f"figure dir missing: {FIG.parent} (expected the paper repo imgs/)")


def load(name):
    p = DUMP / f"{name}.txt"
    return set(p.read_text().splitlines()) if p.exists() else None


rows = []
checked_t1a = checked_phi = 0
for b in BENCHES:
    none, log = load(f"{b}-null-clean"), load(f"{b}-null+log-clean")
    sc, clean = load(f"{b}-string-constant-clean"), load(f"{b}-llm-clean")
    if None in (none, log, sc, clean):
        sys.exit(f"missing baseline dumps for requested bench {b}: need "
                 f"{b}-{{null,null+log,string-constant,llm}}-clean.txt in {DUMP}")
    gt = log - none
    if not gt:
        sys.exit(f"empty GT for {b}: {b}-null+log-clean adds nothing over null")
    sc_recall = len(sc & gt) / len(gt)
    # Pollution baseline: the UNION of all clean draws (<b>-llm-clean*.txt).
    # 2026-07-10 finding: the site prompt embeds fire-time fixpoint state, and the
    # solver schedule is not deterministic across JVM runs, so a site can fire with
    # different evidence maturity -> different cached answer -> the clean reach set
    # itself has several attractors (fop: +/-16 methods; luindex/antlr/hsqldb stable
    # over 3 draws). Comparing one corrupt draw against one clean draw conflates that
    # jitter with corruption-caused pollution; the union baseline removes exactly the
    # jitter (anything reached by SOME clean draw is not corruption-caused).
    clean_union = set()
    for p in sorted(DUMP.glob(f"{b}-llm-clean*.txt")):
        clean_union |= set(p.read_text().splitlines())
    n_draws = len(list(DUMP.glob(f"{b}-llm-clean*.txt")))
    print(f"{b}: pollution baseline = union of {n_draws} clean draws "
          f"({len(clean_union)} methods; single draw {len(clean)})")

    def metrics(reach):
        added = reach - none
        rec = len(reach & gt) / len(gt)
        prec = 1.0 if not added else len(reach & gt) / len(added)
        return rec, prec, len(reach - clean_union), len(reach - log)

    rows.append((b, "clean", 0.0, 1, *metrics(clean)))
    for m in MODES:
        for rate in RATES:
            seeds = [1, 2, 3]  # missing (mode, rate, seed) points WARN+skip below
            for s in seeds:
                reach = load(f"{b}-llm-{m}-{rate:.2f}-s{s}")
                if reach is None:
                    print(f"WARN missing {b} {m} {rate} s{s}")
                    continue
                rec, prec, poll, extra = metrics(reach)
                rows.append((b, m, rate, s, rec, prec, poll, extra))
                # Theorem-level self-checks (the claims the paper text makes):
                assert rec >= sc_recall - 1e-9, \
                    f"T1a VIOLATED: {b} {m} {rate} recall {rec} < sc {sc_recall}"
                checked_t1a += 1
                if m == "random":
                    assert poll == 0, \
                        f"Phi leak: random-mode pollution {poll} on {b}"
                    checked_phi += 1

with open(OUT_CSV, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["bench", "mode", "rate", "seed", "recall", "precision",
                "pollution_vs_clean", "extra_vs_log"])
    w.writerows(rows)
print(f"wrote {OUT_CSV} ({len(rows)} rows)")
print(f"self-checks passed: T1a recall floor on {checked_t1a} points, "
      f"random-mode pollution==0 on {checked_phi} points")

fig, axes = plt.subplots(1, 3, figsize=(11, 3.2))
for ax, metric, idx in zip(axes, ["recall", "precision", "pollution"], [4, 5, 6]):
    for m, style in zip(MODES, ["-o", "-s", "-^"]):
        for b in BENCHES:
            pts = sorted((r[2], r[idx]) for r in rows
                         if r[0] == b and r[1] == m and r[3] == 1)
            base = [r for r in rows if r[0] == b and r[1] == "clean"][0]
            xs = [0.0] + [p[0] for p in pts]
            ys = [base[idx]] + [p[1] for p in pts]
            ax.plot(xs, ys, style, alpha=0.6, markersize=3,
                    label=m if b == BENCHES[0] else None)
    ax.set_xlabel("corruption rate")
    ax.set_title(metric)
axes[0].legend(fontsize=7)
fig.tight_layout()
fig.savefig(FIG)
print(f"wrote {FIG}")
