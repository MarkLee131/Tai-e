# Task 8 Report: Flash-Lite Model + Cost/Time/#Queries as Measured Dimensions

## Summary

Six implementation tasks (Tasks 1–6) completed across three change-sets (A/B/C) on branch `llm-pta`. All tests green.

---

## Part A: Flash-Lite Model Default (commit `649593a6`)

**`src/main/resources/tai-e-analyses.yml`**
- Line 24 comment updated from `"Arm③ (LlmFactPlugin)"` to `"Arm③ (advanced:llm-cafd / LlmWrapperProposer)"`.
- `llm-model` default changed: `gemini-2.0-flash` → `gemini-2.5-flash-lite`.

**`src/main/java/pta/arm1/ArmOracleFactory.java`**
- `IN_RATE` comment updated: "Rough Gemini-flash pricing" → "Gemini Flash-Lite pricing ($0.10/1M input, $0.40/1M output)".
- `IN_RATE = 0.10 / 1_000_000` → `1e-7` (identical numeric value, cleaner literal).
- `OUT_RATE = 0.40 / 1_000_000` → `4e-7` (identical numeric value).
- Default model string in `fromOptions`: `"gemini-2.0-flash"` → `"gemini-2.5-flash-lite"`.
- Added `hasOracle()` public static method (needed by Task 3).

---

## Part B1: CountingOracle (commit `d0bb3eea`)

**New `src/main/java/pta/llm/CountingOracle.java`**
- Implements `LlmOracle`, wraps a delegate.
- `ask(LlmQuery)` delegates then increments `queries` and accumulates `costUsd += resp.estCostUsd()`.
- `queryCount()` returns `long`; `totalCostUsd()` returns `double`.

**New `src/test/java/pta/llm/CountingOracleTest.java`** — 5 TDD tests:
- `initialCountAndCostAreZero`
- `countIncrementsOnEachAsk`
- `costAccumulates`
- `cacheHitCostIsZero` (MockOracle returns `estCostUsd = 0.0`)
- `delegateResponseIsForwarded`

---

## Part B2: ConfigRunner.RunResult (commit `744aaa26`)

**`src/main/java/pta/eval/ConfigRunner.java`** — full rewrite:
- Added `record RunResult(MetricCollector.Metrics metrics, long timeMs, long memMb, double costUsd, long llmQueries)` nested inside `ConfigRunner`.
- `run()` return type changed from `MetricCollector.Metrics` to `RunResult`.
- `timeMs` wired through via `System.nanoTime()` (was measured but discarded before).
- `memMb` measured via `(Runtime.getRuntime().totalMemory() - freeMemory()) / (1024*1024)` after `Main.main()`.
- For arm① configs (`ptaArgs` contains `advanced:llm` but not `advanced:llm-cafd`): injects `CountingOracle(buildArm1BaseOracle(config))` via `pta.arm1.ArmOracleFactory.setOracle()` if `!arm1.ArmOracleFactory.hasOracle()` (respects existing sweep override).
- For arm② configs: wraps existing `injectArm2Oracle` with `CountingOracle` when `!LlmReflectionModel.hasOracle()`.
- For arm③ configs (`ptaArgs` contains `advanced:llm-cafd`): injects `CountingOracle(buildArm3BaseOracle(config))` via `pta.arm3.ArmOracleFactory.setOracle()` if `!arm3.ArmOracleFactory.hasOracle()`.
- Baselines (no arm tag) → `llmQueries = 0`, `costUsd = 0.0`.
- Oracle is cleared in `finally` block only if injected by this runner.

**`src/main/java/pta/arm3/ArmOracleFactory.java`**
- Added `hasOracle()` public static method (mirrors arm1).

**`src/main/java/pta/eval/RobustnessSweep.java`**
- Updated `runner.run(...)` call to `.metrics()` to unwrap `RunResult`.

**`src/test/java/pta/eval/ConfigRunnerTest.java`** — 4 TDD tests:
- `b1IsStrictlyMorePreciseThanB0OnBoxAlias` (updated to use `RunResult.metrics()`)
- `timeMsIsPositive` (new — asserts `timeMs > 0`)
- `baselineHasZeroCostAndZeroQueries` (new — asserts B0 has 0 queries, 0 cost)
- `arm1RunWithMockReportsPositiveLlmQueries` (new — uses `SweepBenchmark` + `sweep-arm1-allyes.txt`; asserts `llmQueries > 0`)

**Deviation from plan:** The plan specified `contextsensitivity.OneObject` for the arm1 test. Diagnostic revealed that Tai-e treats this class as phantom when the classpath is `src/test/resources/pta` (the two-directory split that `Tests.testPTA` uses is not replicated by `ConfigRunner`), resulting in 0 candidate methods. Changed to `SweepBenchmark` with `sweep-arm1-allyes.txt`, which is already confirmed to produce arm1 queries in `RobustnessSweepTest.soundArm1RecallStaysOneAcrossAllErrorRates`.

---

## Part B3: MetricCollector CSV Columns (commit `0fa8d35b`)

**`src/main/java/pta/eval/MetricCollector.java`**
- `CSV_HEADER` extended: appended `costUsd,llmQueries` → 12 columns total.
- `toCsvRow(String, String, long, long)` → `toCsvRow(String, String, long, long, double, long)` — two new params `costUsd`, `llmQueries`; format string extended with `%.6f,%d`.

**`src/test/java/pta/eval/MetricCollectorTest.java`**
- `EXPECTED_CSV_COLUMNS` 10 → 12.
- `csvHeaderHasCorrectColumnsInOrder`: asserts `cols[10] = "costUsd"`, `cols[11] = "llmQueries"`.
- `collectsReachableMethodsAndAvgPtsSizeAndCsvShape`: call updated to 6-arg form; asserts `parts[10] = "0.000000"`, `parts[11] = "0"`.

---

## Part B4: report.py Cost Columns + Plot (commit `03c0c760`)

**`eval/report.py`**
- Removed unused `import os`.
- `TABLE_COLS` extended with `"costUsd"`, `"llmQueries"`.
- `_make_comparison_table`: `numeric_cols` extended with these two.
- New `_make_cost_precision_plot(results, outdir)`: scatter plot x=`costUsd` (mean per config), y=`avgPtsSize` (mean per config). Baselines at `costUsd=0` colored steel-blue, LLM arms colored dark-orange. Saved as `cost_vs_precision.png`.
- `generate_report` calls `_make_cost_precision_plot` and prints its filename.

**`eval/test_report.py`**
- Removed unused `import os`, `import tempfile`.
- `RESULTS_CSV` extended with `costUsd,llmQueries` columns; arm rows A1/A2/A3 have non-zero cost/queries; baseline rows have zeros.
- `test_comparison_table_has_one_row_per_config`: filter updated to include `costUsd`/`llmQueries` in header cell set.
- New `test_cost_vs_precision_png_created`: asserts `cost_vs_precision.png` is created and non-empty.
- New `test_comparison_table_has_cost_and_query_columns`: asserts `costUsd` and `llmQueries` appear in table header.

---

## Part C: Stale Comment Cleanup (commit `127b06ad`)

**`src/main/java/pta/eval/UnsoundCafdStylePlugin.java`**
- `"ConsistencyEngine rejection of contradictory never-alias facts"` → `"LlmWrapperProposer / WrapperDetector structural confirmation (advanced:llm-cafd)"` (Javadoc bullet ~line 111).
- `"NO ConsistencyEngine check (this is the unsoundness)."` → `"NO structural check (this is the unsoundness; arm③ would confirm via LlmWrapperProposer + WrapperDetector before applying)."` (~line 140).
- `"Helpers (mirrored from LlmFactPlugin for self-containment)"` → `"Helpers (self-contained; mirrors simple type-name extraction logic)"` (~line 213).

**`src/test/java/pta/eval/RobustnessSweepTest.java`**
- `"arm ③'s ConsistencyEngine would reject"` → `"arm ③'s LlmWrapperProposer/WrapperDetector would reject"` (~line 224).
- `"ConsistencyEngine: alias(ARunner,ARunner) exists"` → `"WrapperDetector: structural check fails"` (~line 240).

**`src/test/resources/pta/eval/SweepBenchmark.java`**
- `"without a ConsistencyEngine check"` → `"without a structural LlmWrapperProposer check"` (~line 37).

---

## How Cost/Queries Are Surfaced Per Config

1. **Arm runs** (`advanced:llm`, `advanced:llm-cafd`, or `pta.arm2.LlmReflectionModel`): `ConfigRunner.run()` wraps the arm's base oracle in a `CountingOracle` before injection. After `Main.main()` returns, `arm{1,2,3}Counter.queryCount()` and `.totalCostUsd()` are read into `RunResult`.
2. **Baseline runs** (B0/B1/B2z/B2s/B3): no oracle injection; `RunResult.llmQueries = 0`, `RunResult.costUsd = 0.0`.
3. **Sweep runs** (RobustnessSweep): the sweep already installs its own oracle override before calling `runner.run()`; `hasOracle()` returns true so `ConfigRunner` skips its own injection and the sweep oracle is used unmodified. `RunResult.llmQueries = 0` in this path (the sweep does not use `RunResult.llmQueries`).
4. **CSV**: `RunResult.costUsd` / `.llmQueries` are passed to `Metrics.toCsvRow(config, bench, timeMs, memMb, costUsd, llmQueries)` → columns 10/11 in the 12-column CSV.
5. **report.py**: `_make_comparison_table` averages `costUsd`/`llmQueries` per config; `_make_cost_precision_plot` uses `costUsd` vs `avgPtsSize` as a new 2D view.

---

## Test Results (Final)

### Java — `./gradlew :test --tests 'pta.*'`

```
BUILD SUCCESSFUL in 21s
```

Total test count (from XML reports): **42 tests** (34 original + 5 `CountingOracleTest` + 3 `ConfigRunnerTest` new assertions).

### Python — `python3 -m pytest eval/test_report.py -v`

```
8 passed in 9.47s
```

(6 original + 2 new: `test_cost_vs_precision_png_created`, `test_comparison_table_has_cost_and_query_columns`)

---

## Commit Range

`649593a6`..`127b06ad` on `llm-pta`:

| Commit | Message |
|--------|---------|
| `649593a6` | feat(llm): switch default model to gemini-2.5-flash-lite with Flash-Lite pricing |
| `d0bb3eea` | feat(llm): add CountingOracle — tracks query count and accumulated cost |
| `744aaa26` | feat(eval): surface timeMs/costUsd/llmQueries in ConfigRunner.RunResult |
| `0fa8d35b` | feat(eval): add costUsd and llmQueries columns to MetricCollector CSV |
| `03c0c760` | feat(eval): add costUsd/llmQueries to report table + cost-vs-precision plot |
| `127b06ad` | chore: replace stale ConsistencyEngine/LlmFactPlugin references in comments |

---

## Deviations from Plan

1. **arm1 test benchmark**: Plan specified `contextsensitivity.OneObject` with `oneobject-mock.txt`. Changed to `SweepBenchmark` + `sweep-arm1-allyes.txt` because the `ConfigRunner` classpath format (`-cp src/test/resources/pta -m contextsensitivity.OneObject`) causes Tai-e to treat the class as phantom, yielding 0 CI pre-analysis candidates and 0 oracle queries. `SweepBenchmark` in `src/test/resources/pta/eval` loads correctly and produces 9 candidates (confirmed by log: "LLM-CS selected 9 of 9 candidate methods").

2. **`import tempfile` in `test_report.py`**: Plan mentioned removing this; it was already not imported (only `import os` needed removal). Both `import os` and `import tempfile` were absent from the updated file.

3. **`RobustnessSweep` updated**: Plan did not explicitly mention updating `RobustnessSweep.java`, but it was necessary because `runner.run()` now returns `RunResult` instead of `MetricCollector.Metrics`. Updated line 173 from `m = runner.run(...)` to `m = runner.run(...).metrics()`.
