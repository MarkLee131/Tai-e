# Staged vs. legacy pipeline: number diff (B-wave, 2026-07-10)

Staged oracle querying (phase-boundary canonical prompts, `arm2.staged=true` default,
merge 48b4b5d0 + 0abad97a) versus the published legacy (fire-once mid-flight) numbers.
Sources: `expStaged_rq1.xml` (v3, failed=0 on every benchmark), regenerated
`adversarial.csv` / `blastradius.csv`, determinism gates in `staged-determinism.txt`.

## RQ1 (tab:rq1): legacy published → staged

| bench | recall | precision | note |
|---|---|---|---|
| luindex  | 1.000 → 1.000 | 1.000 → 1.000 | unchanged |
| antlr    | 1.000 → 1.000 | 0.943 → 0.943 | unchanged (abstract CodeGenerator answer preserved) |
| bloat    | 1.000 → 1.000 | 0.996 → 0.994 | |
| lusearch | 1.000 → 1.000 | 1.000 → 1.000 | unchanged |
| chart    | 0.984 → 0.984 | 0.992 → 0.987 | |
| hsqldb   | 0.970 → **1.000** | 0.990 → 0.990 | **reaches the dynamic upper bound** (now 5 benchmarks at 1.000) |
| fop      | 0.930 → 0.928 | 0.877 → 0.870 | deterministic single outcome replaces bistable 1545/1558 attractors |
| jython   | 0.901 → 0.901 | 0.980 → 0.971 | |
| xalan    | 0.494 → 0.496 | 0.977 → 0.947 | |
| pmd      | 0.299 → 0.310 | 0.983 → **0.743** | canonical mature prompts fire the hard library-side sites where the oracle is least reliable (RQ6: 50% hallucination rate); the injected extras are wrong-but-loadable, fence-bounded — cor:tight made visible on a real benchmark |
| eclipse  | 0.125 → 0.125 | 0.989 → 0.960 | |

Headline updates: recall claims unchanged ("0.90–1.00 on 8 of 11"); upper-bound-exact 4 → 5;
precision range 0.87–1.00 → **0.74–1.00** (10 of 11 within 0.87–1.00; pmd 0.74 is the
explained outlier). Wall-clock: staged adds phases; re-measure timings column if quoted.

## RQ8 (adversarial): staged regeneration — headline numbers UNCHANGED

T1a recall floor 120/120 points; random-mode pollution 0 at 48/48; only loadable pollutes,
max 58 spurious methods (fop, rate 0.75, seed 2), min precision 0.70 (antlr loadable 1.0 s2).
Blast radius identical in structure: luindex findClass S=522/|GT|=525 share 1.000, BR=33
(LusearchHarness); antlr 783/0.562 + two-stage cascade (Tool.doEverything downstream); all
other BR=0. NEW under staged: the three extra clean draws are byte-identical on all four
benchmarks — the union-of-clean-draws pollution baseline collapses to a single draw (the
nondeterminism it compensated for is gone).

## Determinism evidence

- B3 gates: fop 3-run byte-identical, twice (pre- and post-grounding-revert); luindex,
  antlr, hsqldb 3-run byte-identical.
- Clean draws within the sweep: 3/3 identical per benchmark (4 benchmarks).
- Legacy before-column: warm-cache attractor flips (fop ±16 methods, ~1/3 of draws);
  legacy cold-cache stab5–7 were byte-identical (single-attractor sampling).
- Staged cold-cache full-suite passes (stagedstab1–3): INVALID — daily API quota exhausted,
  all live queries failed; correctly self-flagged by the new failed-query FLOOR warning
  (files quarantined as INVALID-quota-*). RERUN PENDING after quota reset; until then the
  paper's determinism claim rests on the byte-identity gates above, which are the
  structural (schedule-independence) evidence; fresh-oracle cold passes add answer-level
  repeatability only.

## Infrastructure fixes shipped en route (all committed)

1. Gemini intermittent 404 → transient/retry (was fail-fast; silently floored 3 benchmarks).
2. failed-query counter in oracleStats + loud FLOOR warning row in the harness (proved its
   worth immediately by catching the quota-exhausted stability passes).
3. Grounding slot: sorted (determinism) but NOT instantiable-filtered (tried, reverted:
   antlr recall 1.000→0.906 — asymmetric loss vs pmd precision gain; causality documented
   in code comments). No-prose answer instruction added to Q_CLASS.

## Paper edits required (B5 checklist)

- tab:rq1: all 11 rows from expStaged_rq1.xml; timing column re-measure or drop per-row times.
- Intro/abstract-adjacent: "0.87–1.00 precision" → "0.74–1.00" (or "0.87–1.00 on 10 of 11,
  pmd 0.74"); "reaches the dynamic upper bound exactly on four" → five (add hsqldb).
- fig:rq1 caption "precision (0.87–1.00 throughout)" → update.
- §4 precision-theorem discussion quoting "0.87–1.00 measured precision" → update + pmd note.
- RQ1 prose: hsqldb joins the upper-bound list; pmd paragraph gains the mature-prompt
  mechanism sentence (cross-ref RQ6 + cor:tight).
- RQ2 fop true-value citation (0.877): re-check — staged fop precision is 0.870; the
  "do NOT round up" memory note applies to the NEW value.
- Setup: strengthen determinism sentence (staged querying at phase boundaries; canonical
  prompts; byte-identical repeated runs) replacing the interim truth-fix.
- RQ8(iv): rewrite the union-baseline note into the finding+fix narrative (two-layer root
  cause: evidence-timing schedule dependence + unordered prompt slots; fixed by staged
  querying + canonical slots; post-fix clean draws identical, baseline collapses).
- RQ4 or RQ8: one sentence on the 404-channel + FLOOR-warning fix (third channel defect,
  joins the RQ4 channel-defect list narrative).
- Threats variance sentences: legacy stab5-7 zero-variance + staged byte-identity gates;
  staged cold-pass rerun pending (do not overclaim).
