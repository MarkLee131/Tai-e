#!/usr/bin/env python3
"""Per-site blast radius (RQ-adv / prop:gating): S(l), S∩GT share, BR(l).

Consumes the dumps written by run_blastradius.sh (tags allsilent / solo-<k> /
mal-<k>) plus the archived clean/baseline dumps, and the site list
../raw/sites-luindex-antlr.txt (bench\tsiteId\tcleanAnswer). Emits
../raw/blastradius.csv and prints the per-benchmark table sorted by S desc.

Metrics per queried site l_k:
  S(l_k)       = |Reach(solo_k) − Reach(allsilent)|     solo contribution
  S_GT_share   = |Reach(solo_k) ∩ GT| / |GT|            share of ground truth
  BR(l_k)      = |Reach(mal_k) − ∪ clean draws|         malicious blast radius
GT = (null+log-clean) − (null-clean). BR is measured against the UNION of all
clean draws (<bench>-llm-clean*.txt), not a single draw: the solver schedule is
not deterministic across JVM runs and the site prompt embeds fire-time fixpoint
state, so a single clean draw conflates schedule jitter with corruption-caused
pollution (see adversarial_report.py; luindex/antlr were verified stable over
3 draws, the union is belt-and-braces).

Self-check (gating concentration): per benchmark, max_k S_GT_share should be
approximately the benchmark's full recall |clean ∩ GT|/|GT| — a single site
carries the whole recall. A FAIL is a REPORTABLE structural finding (e.g. a
two-stage gating cascade where the downstream site needs its own answer and no
solo run can cover both stages), NOT an error to mask; the script prints the
numbers and still writes the CSV.
"""
import csv
from pathlib import Path

RAW = Path(__file__).resolve().parent.parent / "raw"
DUMP = RAW / "reachdumps"
SITES = RAW / "sites-luindex-antlr.txt"
OUT_CSV = RAW / "blastradius.csv"

# Loadable-but-wrong class used by the mal-<k> runs; MUST mirror WRONG in
# run_blastradius.sh (rationale documented there: LusearchHarness is a foreign
# harness inside luindex.jar — loadable AND Benchmark-fence-passing;
# ParserEventSupport is a loadable antlr.jar class outside every fence cone).
WRONG = {
    "luindex": "dacapo.lusearch.LusearchHarness",
    "antlr": "antlr.debug.ParserEventSupport",
}


def load(name):
    p = DUMP / f"{name}.txt"
    if not p.exists():
        raise SystemExit(f"missing dump {p} — run run_blastradius.sh first")
    return set(p.read_text().splitlines())


sites = {}  # bench -> [(siteId, cleanAnswer)]
for line in SITES.read_text().splitlines():
    bench, site, answer = line.split("\t")
    sites.setdefault(bench, []).append((site, answer))

rows = []
checks = []
for bench, blist in sites.items():
    none, log = load(f"{bench}-null-clean"), load(f"{bench}-null+log-clean")
    clean = load(f"{bench}-llm-clean")
    allsilent = load(f"{bench}-llm-allsilent")
    gt = log - none
    if not gt:
        raise SystemExit(f"empty GT for {bench}")
    clean_union = set()
    for p in sorted(DUMP.glob(f"{bench}-llm-clean*.txt")):
        clean_union |= set(p.read_text().splitlines())
    full_recall = len(clean & gt) / len(gt)
    print(f"\n== {bench}: |GT|={len(gt)} full_recall={full_recall:.3f} "
          f"|allsilent|={len(allsilent)} clean_union={len(clean_union)} "
          f"(over {len(list(DUMP.glob(f'{bench}-llm-clean*.txt')))} draws) "
          f"wrong={WRONG[bench]}")
    bench_rows = []
    for k, (site, answer) in enumerate(blist, start=1):
        solo, mal = load(f"{bench}-llm-solo-{k}"), load(f"{bench}-llm-mal-{k}")
        s = len(solo - allsilent)
        share = len(solo & gt) / len(gt)
        br = len(mal - clean_union)
        bench_rows.append((bench, site, answer, WRONG[bench], s, share, br))
    bench_rows.sort(key=lambda r: -r[4])
    print(f"{'site':<58} | {'S':>5} | {'S∩GT share':>10} | {'BR':>5}")
    for r in bench_rows:
        print(f"{r[1]:<58} | {r[4]:>5} | {r[5]:>10.3f} | {r[6]:>5}")
    rows += bench_rows
    max_share = max(r[5] for r in bench_rows)
    ok = abs(max_share - full_recall) <= 0.02
    checks.append((bench, max_share, full_recall, ok))

with open(OUT_CSV, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["bench", "site", "cleanAnswer", "wrongClass",
                "S", "S_GT_share", "BR"])
    w.writerows(rows)
print(f"\nwrote {OUT_CSV} ({len(rows)} rows)")

print("\n[self-check] gating concentration: max_k S_GT_share ≈ full recall")
for bench, max_share, full_recall, ok in checks:
    print(f"  {bench:<8} max_S_GT_share={max_share:.3f} "
          f"full_recall={full_recall:.3f} -> {'PASS' if ok else 'FAIL'}"
          + ("" if ok else "  (reportable finding: no single solo site carries"
                           " the full recall — e.g. a multi-stage gating"
                           " cascade)"))
