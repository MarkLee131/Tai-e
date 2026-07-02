# arm2 (Reflex) experiment archive

Raw evidence and reproduction commands for every experiment in the paper
*LLM Proposes, Sound Logic Disposes* (repo branch `llm-pta`). All numbers in
`PTA-Paper.tex` are regenerated from the archived JUnit XMLs by:

```bash
cd scripts && python3 parse_results.py
```

The harness is `pascal.taie.analysis.pta.ReflectionRecallEval`, driven by the
`reflRecall` Gradle task (see `../../build.gradle.kts`). Live runs (`-ParmLive`)
call Gemini; a warm response cache (`.llm-cache`) makes them cheap and
deterministic. Ground truth for recall is the TamiFlex reflection log:
`GT = reachable(log) \ reachable(no-reflection)`.

## Files → paper artifact

| file (`raw/`) | RQ / table | reproduce |
|---|---|---|
| `exp1.xml` `exp3.xml` `exp4.xml` | RQ1 `tab:rq1` (recall/precision, all 11) | `./gradlew reflRecall -PreflBenchmarks=<subset> -ParmLive` |
| `expCG.xml` | RQ5 `tab:cg` (call-graph size) + RQ6a (disposer) | `./gradlew reflRecall -PreflBenchmarks=<all11> -ParmLive` |
| `expModel_flashlite.xml` `expModel_20flash.xml` | RQ6b cross-model | `-PreflModel=gemini-2.5-flash-lite -PreflCacheSalt=fl` |
| `expB_none.xml` `expB_id.xml` | RQ2 context tiers (none/id) | `-PreflContext=none` / `-PreflContext=id` |
| `expA_*.xml` | RQ3 ablation matrix | `-PreflAblate=selfInference` (etc.) |
| `expD.xml` | RQ3 bootstrap-substitution cascade | `-PreflDebug -PreflBootstrapLog=scripts/luindex-bootstrap.log` |
| `expC.xml` `expE_stab{2,3,4}.xml` | RQ4 stability (4 fresh-oracle runs) | `-PreflCacheSalt=<salt>` (cold cache) |
| `expF.xml` | RQ4 2-object context-sensitivity spot check | `-PreflCs=2-obj` |
| `expG.xml` | RQ4 per-site forName-target P/R | `-PreflDumpTargets` → `scripts/persite.py` |
| `expRealworld.xml` `smoke_jedit.xml` | RQ7 `tab:rw` (real-world apps) | `-PreflBenchmarks=columba-1.4,gruntspud-0.4.6,…` |
| `expCompose.xml` | Reflex⊕SOLAR composition (DaCapo precision cost) | `-Darm2.solarCompose` |

## `taint/` — Log4Shell downstream case study (CVE-2021-44228)

Tai-e taint analysis on log4j-core 2.14.0 under four reflection settings.
`options-<mode>.yml` are the per-setting configs; `run-<mode>.log` the outputs.

```bash
./gradlew run --args="--options-file eval/arm2/taint/options-<mode>.yml"
```

Result: string-constant **0** flows, SOLAR **1**, Reflex-alone **0**,
`llm-composed` (`-Darm2.solarCompose`) **1** — the source→JNDI-lookup flow. The
flow is `Method.invoke` (type)-gated, so Reflex's name resolution recovers it
only when composed on top of SOLAR (opt-in; costs DaCapo precision — see
`expCompose.xml`).

## `scripts/`

- `parse_results.py` — regenerates every paper table from `raw/` (self-check).
- `persite.py` — per-site forName-target P/R over `expG.xml` (RQ4 site granularity).
- `run_*.sh` — the exact batch runners used (archival; contain absolute
  scratchpad paths from the original run — use the `reproduce` column above for
  fresh runs).
- `luindex-bootstrap.log` — the 2-line bootstrap-only reflection log for RQ3(i).

## Notes

- `benchmark-info.yml` (repo root of `java-benchmarks/`) registers all 18
  benchmarks: 11 DaCapo-2006 (no dash in id) + 7 real-world apps (id-version).
- Real-world apps get generic identity + their actual main class as context
  (not the DaCapo launcher convention) — same grounding mechanism, no
  benchmark-specific trick.
- Precision is a lower bound: the dynamic log under-approximates, so
  sound-but-not-exercised targets count against us.
