# B4b: RQ2/3/6/7 + compose under the staged pipeline — vs legacy paper values

Sources: `raw/staged/*.xml` (this directory). Legacy = the published tables/prose in
`PTA-Paper-POPL.tex`. Battery history: ablations+expD ran in the original B4b pass;
the remainder ran via `scripts/run_b4b_remaining.sh` after the session-limit casualty.

## RQ3 ablations + expD (luindex, antlr) — REPRODUCE EXACTLY
Seeding-off antlr 0.562, subtypeExpansion-off antlr 0.565 (paper prose values verbatim);
luindex insensitive (1.000) in both; grounding/selfInference/serviceLoader ablations and the
bootstrap-substitution cascade match the paper's RQ3 claims. → paper RQ3: NO changes.

## RQ2 context tiers (bloat, chart, hsqldb, fop) — MAJOR POSITIVE CHANGE
| bench | none (legacy → staged) | id (legacy → staged) | full (staged, =tab:rq1) |
|---|---|---|---|
| bloat  | 0.004 → 0.004 | 0.004 → 0.004 (wrong-pick: EDU.purdue…Benchmark) | 1.000 |
| chart  | 0.003 → 0.003 | 0.018 → **0.984** | 0.984 |
| hsqldb | 0.051 → 0.051 | 0.061 → **1.000** | 1.000 |
| fop    | 0.005 → 0.005 | 0.010 → **0.928** | 0.928 |

Mechanism (verified in the query log): at the id tier the model now answers the correct
harness class (dacapo.chart.ChartHarness etc.) picked from the GROUNDING slot — the
machine-derived list of classpath classes matching the application id (sorted, canonical,
part of the determinism fix). No naming template appears in any prompt. bloat fails by
picking a plausible-but-wrong loadable candidate (cor:tight's regime; the full tier's
convention sentence resolves the ambiguity).
PAPER IMPACT (ε/B5-follow-up): RQ2's narrative strengthens materially — identity +
machine-derived classpath grounding reaches 0.93–1.00 on 3/4 tier benchmarks; the
convention sentence is only needed for ambiguous ids. The threats/leakage paragraph
gets stronger (success attributable to machine-enumerable facts + selection). This
delivers E3's machine-derived-context goal on DaCapo incidentally.
VERIFICATION QUEUED before paper claims: id-tier on all 11 benchmarks (was only the
4 tier benchmarks); the improvement's provenance is the staged+sorted pipeline (B2b),
absent in legacy — a fresh-cache repeat is redundant given staged determinism but one
confirming pass is cheap.

## RQ6 cross-model (luindex, antlr)
- flash-lite: luindex 1.000, antlr 0.097 (legacy antlr 0.054 — same weak-model shape).
- gemini-2.0-flash: RETIRED/unavailable at the API (all queries fail after retries;
  FLOOR-flagged, XML kept for the record). Paper options: keep the legacy 2.0-flash row
  with a provenance/date note, or substitute a current weak model (deepseek-chat via the
  E2 adapter is a candidate third family anyway). → decision at ε/B5-follow-up.

## RQ7 real-world (7 apps)
| app | legacy r/p | staged r/p | note |
|---|---|---|---|
| columba   | 0.991/0.989 | 0.992/0.989 | ≈ |
| gruntspud | 0.625/0.417 | **0.338**/0.397 | REGRESSION — staged fired only 2 queries (legacy ~30); root-cause diagnosis in progress; do NOT update the paper row until resolved |
| jedit     | 0.800/0.750 | 0.800/0.750 | = |
| freecol   | 0.925/0.718 | **0.979**/0.727 | improvement |
| briss     | 0.300/1.000 | 0.300/1.000 | = |
| soot      | 1.000/0.500 | 1.000/**0.100** | precision drop (GT=1; +9 extras) |
| findbugs  | 0.059/0.900 | 0.059/0.900 | = |

## Compose (RQ5's opt-in composition, 11 DaCapo)
jython 0.911 and eclipse 0.175 reproduce the paper's composed values exactly; hsqldb
composed precision 0.805 (paper 0.800). → paper compose numbers: at most the 0.805
touch-up.

## Paper-edit queue derived from this battery
1. RQ2 table+prose rewrite (pending the 11-benchmark id-tier verification).
2. RQ7 gruntspud row: BLOCKED on the regression diagnosis; freecol/soot rows update.
3. RQ6: 2.0-flash retirement handling (provenance note or third-family substitute).
4. Compose: hsqldb 0.800→0.805 touch-up if kept.
