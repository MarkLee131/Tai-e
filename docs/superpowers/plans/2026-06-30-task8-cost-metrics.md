# Task 8: Flash-Lite Model + Cost/Time/#Queries as Measured Dimensions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch default Gemini model to `gemini-2.5-flash-lite`, add a `CountingOracle` wrapper, surface `timeMs`/`costUsd`/`llmQueries` in `ConfigRunner.RunResult`, plumb them through `MetricCollector` CSV + `report.py`, and clean up stale comments.

**Architecture:** Three independent change-sets (A/B/C) applied in sequence. Part A is purely textual. Part B introduces a new class (`CountingOracle`), extends `ConfigRunner` to return a `RunResult` record, updates `MetricCollector.Metrics.toCsvRow` with two new columns, and updates `report.py`. Part C removes stale references. All changes are test-first (write failing test → implement → verify green).

**Tech Stack:** Java 17 records, JUnit 5, `./gradlew :test --tests 'pta.*'`, Python 3 / pytest, pandas, matplotlib.

## Global Constraints

- Branch: `llm-pta`. Work directly, no branch switch.
- JDK 17. No new Gradle dependencies.
- `./gradlew :test --tests 'pta.*'` must stay green (currently 34 tests) after every commit.
- `python3 -m pytest eval/test_report.py -v` must stay green (currently 6 tests) after every commit.
- Checkstyle warning-only (do not break CI even if warnings appear).
- TDD: write the failing test first, then the implementation.
- All file paths in this plan are relative to `/home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e` (hereafter `$REPO`).

---

## File Map

### New files
- `$REPO/src/main/java/pta/llm/CountingOracle.java` — wraps any `LlmOracle` delegate, counts `ask()` calls, accumulates `totalCostUsd()`
- `$REPO/src/test/java/pta/llm/CountingOracleTest.java` — unit tests for `CountingOracle`

### Modified files
- `$REPO/src/main/resources/tai-e-analyses.yml` — change `gemini-2.0-flash` → `gemini-2.5-flash-lite` (line 27); fix stale comment on line 24
- `$REPO/src/main/java/pta/arm1/ArmOracleFactory.java` — change default model string on line 97; update comment on `IN_RATE`/`OUT_RATE` to say "Flash-Lite pricing"
- `$REPO/src/main/java/pta/eval/ConfigRunner.java` — add `RunResult` record; change `run()` return type to `RunResult`; wire `timeMs`; instrument arm① and arm③ with `CountingOracle`; measure `memMb`
- `$REPO/src/main/java/pta/eval/MetricCollector.java` — add `costUsd`/`llmQueries` parameters to `toCsvRow`; update `CSV_HEADER`
- `$REPO/src/test/java/pta/eval/MetricCollectorTest.java` — update column count and column assertions for new CSV shape
- `$REPO/src/test/java/pta/eval/ConfigRunnerTest.java` — add assertions: `timeMs > 0`; arm mock run has `llmQueries > 0`; baseline has `llmQueries == 0`
- `$REPO/eval/report.py` — add `costUsd`/`llmQueries` to `TABLE_COLS`; add `cost_vs_precision.png` plot; remove unused `import os`
- `$REPO/eval/test_report.py` — update `RESULTS_CSV` canned data with two new columns; update assertions for new columns; remove unused `import os`, `import tempfile`
- `$REPO/src/main/java/pta/eval/UnsoundCafdStylePlugin.java` — fix stale comments ~lines 111, 140, 213
- `$REPO/src/test/java/pta/eval/RobustnessSweepTest.java` — fix stale comments ~lines 224, 240
- `$REPO/src/test/resources/pta/eval/SweepBenchmark.java` — fix stale comment ~line 37

---

### Task 1: Part A — Flash-Lite model default + comment

**Files:**
- Modify: `$REPO/src/main/resources/tai-e-analyses.yml:27`
- Modify: `$REPO/src/main/java/pta/arm1/ArmOracleFactory.java:59-61,97`

**Interfaces:**
- Produces: `ArmOracleFactory.fromOptions` defaults `llm-model` to `"gemini-2.5-flash-lite"` with `IN_RATE = 1e-7`, `OUT_RATE = 4e-7`

- [ ] **Step 1: Run baseline tests to confirm they are currently green**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 34 tests passed.

- [ ] **Step 2: Edit `tai-e-analyses.yml` — change model default and fix llm-mock-file comment**

In `$REPO/src/main/resources/tai-e-analyses.yml`:

Change line 24-26 from:
```yaml
    llm-mock-file: null # shared by Arm① (selective-CS) and Arm③ (LlmFactPlugin):
    # path to a canned mock-oracle answer file; when set, an offline MockOracle
    # is used instead of querying the live LLM
    llm-model: gemini-2.0-flash # Arm① model id for the live LLM oracle
```
to:
```yaml
    llm-mock-file: null # shared by Arm① (selective-CS) and Arm③ (advanced:llm-cafd /
    # LlmWrapperProposer): path to a canned mock-oracle answer file; when set, an
    # offline MockOracle is used instead of querying the live LLM
    llm-model: gemini-2.5-flash-lite # Arm① model id for the live LLM oracle
```

- [ ] **Step 3: Edit `arm1/ArmOracleFactory.java` — update rate comment + default model string**

In `$REPO/src/main/java/pta/arm1/ArmOracleFactory.java`:

Change lines 57-61 from:
```java
    /** Rough Gemini-flash pricing (USD per token) for the budget meter. */
    private static final double IN_RATE = 0.10 / 1_000_000;

    private static final double OUT_RATE = 0.40 / 1_000_000;
```
to:
```java
    /** Gemini Flash-Lite pricing (USD per token): $0.10/1M input, $0.40/1M output. */
    private static final double IN_RATE = 1e-7;  // $0.10 / 1_000_000

    private static final double OUT_RATE = 4e-7; // $0.40 / 1_000_000
```

Change line 97 from:
```java
        String model = optString(options, "llm-model", "gemini-2.0-flash");
```
to:
```java
        String model = optString(options, "llm-model", "gemini-2.5-flash-lite");
```

- [ ] **Step 4: Run tests to confirm nothing broke**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 34 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add src/main/resources/tai-e-analyses.yml src/main/java/pta/arm1/ArmOracleFactory.java
git commit -m "feat(llm): switch default model to gemini-2.5-flash-lite with Flash-Lite pricing"
```

---

### Task 2: Part B1 — CountingOracle unit test + implementation

**Files:**
- Create: `$REPO/src/test/java/pta/llm/CountingOracleTest.java`
- Create: `$REPO/src/main/java/pta/llm/CountingOracle.java`

**Interfaces:**
- Produces: `CountingOracle(LlmOracle delegate)` — wraps delegate
- Produces: `LlmResponse ask(LlmQuery q)` — delegates, increments count, accumulates `estCostUsd`
- Produces: `long queryCount()` — number of `ask()` calls made
- Produces: `double totalCostUsd()` — sum of `response.estCostUsd()` from all `ask()` calls
- Consumes: `LlmOracle.ask(LlmQuery)`, `LlmResponse.estCostUsd()`

- [ ] **Step 1: Write the failing test**

Create `$REPO/src/test/java/pta/llm/CountingOracleTest.java`:

```java
package pta.llm;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CountingOracle}.
 */
class CountingOracleTest {

    /** A MockOracle that returns a response with known cost. */
    private static LlmOracle costOracle(double costPerCall) {
        return q -> new LlmResponse("answer", false, costPerCall);
    }

    @Test
    void initialCountAndCostAreZero() {
        CountingOracle co = new CountingOracle(costOracle(0.5));
        assertEquals(0, co.queryCount(), "queryCount must be 0 before any ask");
        assertEquals(0.0, co.totalCostUsd(), 1e-12, "totalCostUsd must be 0.0 before any ask");
    }

    @Test
    void countIncrementsOnEachAsk() {
        CountingOracle co = new CountingOracle(costOracle(0.0));
        co.ask(new LlmQuery("k", "p", "id1"));
        co.ask(new LlmQuery("k", "p", "id2"));
        co.ask(new LlmQuery("k", "p", "id3"));
        assertEquals(3, co.queryCount(), "queryCount must equal number of ask() calls");
    }

    @Test
    void costAccumulates() {
        CountingOracle co = new CountingOracle(costOracle(0.25));
        co.ask(new LlmQuery("k", "p", "id1"));
        co.ask(new LlmQuery("k", "p", "id2"));
        assertEquals(0.50, co.totalCostUsd(), 1e-12, "totalCostUsd must sum estCostUsd from each response");
    }

    @Test
    void cacheHitCostIsZero() {
        // Cache hits return estCostUsd = 0.0 (from MockOracle)
        MockOracle mock = new MockOracle(Map.of("id1", "cached"), "default");
        CountingOracle co = new CountingOracle(mock);
        LlmResponse resp = co.ask(new LlmQuery("k", "p", "id1"));
        assertEquals(1, co.queryCount(), "cache hit still counts as a query");
        assertEquals(0.0, co.totalCostUsd(), 1e-12, "cache hit has 0 cost");
        assertEquals("cached", resp.raw(), "delegate response is forwarded");
    }

    @Test
    void delegateResponseIsForwarded() {
        LlmOracle delegate = q -> new LlmResponse("hello-" + q.contextId(), false, 1.0);
        CountingOracle co = new CountingOracle(delegate);
        LlmResponse r = co.ask(new LlmQuery("k", "p", "ctx99"));
        assertEquals("hello-ctx99", r.raw(), "raw response text must be forwarded from delegate");
        assertEquals(1.0, r.estCostUsd(), 1e-12, "estCostUsd must be forwarded from delegate");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.llm.CountingOracleTest' 2>&1 | tail -20
```
Expected: FAIL — `CountingOracle` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `$REPO/src/main/java/pta/llm/CountingOracle.java`:

```java
package pta.llm;

/**
 * Wraps any {@link LlmOracle} delegate, counting the number of {@link #ask}
 * calls and accumulating the total estimated cost (USD) from
 * {@link LlmResponse#estCostUsd()}. Cache hits have {@code estCostUsd = 0.0};
 * live calls carry a positive cost. Both are counted as queries.
 *
 * <p>Use this to instrument arm oracles inside {@link pta.eval.ConfigRunner}
 * so that {@code llmQueries} and {@code costUsd} can be surfaced per run.
 */
public final class CountingOracle implements LlmOracle {

    private final LlmOracle delegate;
    private long queries = 0;
    private double costUsd = 0.0;

    public CountingOracle(LlmOracle delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse ask(LlmQuery q) {
        LlmResponse resp = delegate.ask(q);
        queries++;
        costUsd += resp.estCostUsd();
        return resp;
    }

    /** Number of {@link #ask} calls made so far. */
    public long queryCount() {
        return queries;
    }

    /** Sum of {@link LlmResponse#estCostUsd()} from all {@link #ask} calls so far. */
    public double totalCostUsd() {
        return costUsd;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.llm.CountingOracleTest' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Run full suite to confirm no regressions**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 39 tests passed (34 old + 5 new).

- [ ] **Step 6: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add src/test/java/pta/llm/CountingOracleTest.java src/main/java/pta/llm/CountingOracle.java
git commit -m "feat(llm): add CountingOracle — tracks query count and accumulated cost"
```

---

### Task 3: Part B2 — ConfigRunner returns RunResult with time/cost/queries

**Files:**
- Modify: `$REPO/src/main/java/pta/eval/ConfigRunner.java`
- Modify: `$REPO/src/test/java/pta/eval/ConfigRunnerTest.java`

**Interfaces:**
- Produces: `record RunResult(MetricCollector.Metrics metrics, long timeMs, long memMb, double costUsd, long llmQueries)` — nested inside `ConfigRunner`
- Produces: `ConfigRunner.run(Config, String, String) -> RunResult` (changed return type)
- Consumes: `CountingOracle(LlmOracle)`, `CountingOracle.queryCount()`, `CountingOracle.totalCostUsd()`
- Consumes: `pta.arm1.ArmOracleFactory.setOracle(LlmOracle)`, `pta.arm1.ArmOracleFactory.clearOracle()`
- Consumes: `pta.arm3.ArmOracleFactory.setOracle(LlmOracle)`, `pta.arm3.ArmOracleFactory.clearOracle()`
- Consumes: `pta.arm2.LlmReflectionModel.setOracle(LlmOracle)`, `pta.arm2.LlmReflectionModel.clearOracle()`, `pta.arm2.LlmReflectionModel.hasOracle()`

**Key logic notes:**
- Arm① config: `ptaArgs` contains `advanced:llm` (not `advanced:llm-cafd`). Check: `config.ptaArgs().contains("advanced:llm") && !config.ptaArgs().contains("advanced:llm-cafd")`.
- Arm② config: `ptaArgs` contains `pta.arm2.LlmReflectionModel` (already handled; extend to wrap with CountingOracle).
- Arm③ config: `ptaArgs` contains `advanced:llm-cafd`.
- For unsound CAFD config: `ptaArgs` contains `pta.eval.UnsoundCafdStylePlugin`; treat like baseline (cost=0, queries=0).
- Baseline configs: no LLM arm present; cost=0, queries=0.
- `memMb` = `(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024)` measured AFTER `Main.main` returns.

- [ ] **Step 1: Write new failing tests in ConfigRunnerTest**

Replace the existing content of `$REPO/src/test/java/pta/eval/ConfigRunnerTest.java` with:

```java
package pta.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link ConfigRunner} and {@link Configs}.
 *
 * <p>Uses {@code BoxAlias} from {@code src/test/resources/pta/eval} as the
 * canonical object-sensitivity benchmark.
 */
public class ConfigRunnerTest {

    private static final String BENCHMARK_CP = "src/test/resources/pta/eval";
    private static final String MAIN_CLASS = "BoxAlias";

    /**
     * B1 must achieve strictly smaller avgPtsSize than B0 (precision sanity).
     * RunResult.metrics() wraps MetricCollector.Metrics.
     */
    @Test
    void b1IsStrictlyMorePreciseThanB0OnBoxAlias() {
        ConfigRunner runner = new ConfigRunner();

        ConfigRunner.RunResult r0 = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        ConfigRunner.RunResult r1 = runner.run(Configs.B1, BENCHMARK_CP, MAIN_CLASS);

        assertNotNull(r0, "B0 (CI) must complete without exception");
        assertNotNull(r1, "B1 (2-obj) must complete without exception");

        assertTrue(r0.metrics().avgPtsSize() > 0,
                "B0 must see non-empty points-to sets");

        assertTrue(r1.metrics().avgPtsSize() < r0.metrics().avgPtsSize(),
                "B1 (2-obj) avgPtsSize=" + r1.metrics().avgPtsSize()
                + " must be < B0 (CI) avgPtsSize=" + r0.metrics().avgPtsSize());
    }

    /**
     * timeMs must be positive (wall-clock is measured and NOT discarded).
     */
    @Test
    void timeMsIsPositive() {
        ConfigRunner runner = new ConfigRunner();
        ConfigRunner.RunResult result = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        assertTrue(result.timeMs() > 0,
                "timeMs must be positive — analysis takes non-zero time");
    }

    /**
     * Baseline configs (no LLM) must have llmQueries == 0 and costUsd == 0.
     */
    @Test
    void baselineHasZeroCostAndZeroQueries() {
        ConfigRunner runner = new ConfigRunner();
        ConfigRunner.RunResult result = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        assertEquals(0, result.llmQueries(),
                "Baseline B0 must have 0 LLM queries");
        assertEquals(0.0, result.costUsd(), 1e-12,
                "Baseline B0 must have 0.0 cost");
    }

    /**
     * Arm① run with a mock oracle must report llmQueries > 0.
     */
    @Test
    void arm1RunWithMockReportsPositiveLlmQueries() {
        ConfigRunner runner = new ConfigRunner();
        // Use the standard arm1 mock file for OneObject benchmark
        String mockFile = "src/test/resources/pta/arm1/oneobject-mock.txt";
        Configs.Config a1 = Configs.a1(mockFile);
        // Run on contextsensitivity/OneObject
        ConfigRunner.RunResult result = runner.run(a1, "src/test/resources/pta",
                "contextsensitivity.OneObject");
        assertTrue(result.llmQueries() > 0,
                "Arm① run with a mock oracle must make at least one LLM query; got "
                + result.llmQueries());
    }
}
```

- [ ] **Step 2: Run tests to verify new tests fail (compile error or assertion fail)**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.eval.ConfigRunnerTest' 2>&1 | tail -20
```
Expected: compile error — `RunResult` does not exist yet and `runner.run()` still returns `MetricCollector.Metrics`.

- [ ] **Step 3: Rewrite ConfigRunner.java with RunResult record and updated run() method**

Replace the full content of `$REPO/src/main/java/pta/eval/ConfigRunner.java` with:

```java
package pta.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pta.arm1.ArmOracleFactory;
import pta.arm2.LlmReflectionModel;
import pta.llm.CountingOracle;
import pta.llm.LlmOracle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process runner that executes a single {@link Configs.Config} on a given
 * benchmark (classpath + main class), times it, measures memory, counts LLM
 * queries and cost, and returns a {@link RunResult}.
 *
 * <h2>World reset between runs</h2>
 * <p>Each call to {@link #run} delegates to {@code pascal.taie.Main.main},
 * which reinitializes {@code pascal.taie.World} before building the new
 * analysis. Callers need no extra cleanup.
 *
 * <h2>Timing and memory</h2>
 * <p>Wall-clock time is measured with {@code System.nanoTime()} around the
 * {@code Main.main} call and converted to milliseconds. Memory ({@code memMb})
 * is approximated via {@code Runtime} after the run.
 *
 * <h2>LLM query/cost instrumentation</h2>
 * <p>For arm configs, the arm's base oracle (from mock file or live) is wrapped
 * in a {@link CountingOracle} before injection so that query count and
 * accumulated cost are available after the run. Baseline configs always
 * produce {@code llmQueries = 0} and {@code costUsd = 0.0}.
 *
 * <h2>Arm ② special handling</h2>
 * <p>{@link pta.arm2.LlmReflectionModel} uses a <em>static</em> oracle field
 * that must be populated before {@code Main.main} is called. When a config's
 * {@code ptaArgs} string contains {@code pta.arm2.LlmReflectionModel}, the
 * runner loads a {@link pta.llm.MockOracle} from {@code llm-mock-file}, wraps
 * it in a {@link CountingOracle}, and injects it via
 * {@link LlmReflectionModel#setOracle}.
 */
public final class ConfigRunner {

    /**
     * Immutable result of a single analysis run.
     *
     * @param metrics    precision metrics from {@link MetricCollector}
     * @param timeMs     wall-clock analysis time in milliseconds
     * @param memMb      approximate heap usage in megabytes after the run
     * @param costUsd    estimated LLM cost in USD (0 for baselines)
     * @param llmQueries number of LLM oracle calls made (0 for baselines)
     */
    public record RunResult(
            MetricCollector.Metrics metrics,
            long timeMs,
            long memMb,
            double costUsd,
            long llmQueries) {
    }

    private static final Logger logger = LoggerFactory.getLogger(ConfigRunner.class);

    /**
     * Fixed PTA options prepended to every config's {@code ptaArgs}.
     */
    private static final List<String> BASE_OPTS = List.of(
            "implicit-entries:false",
            "only-app:true",
            "distinguish-string-constants:all");

    private static final String ARM2_PLUGIN = "pta.arm2.LlmReflectionModel";
    private static final String ARM1_ADVANCED = "advanced:llm";
    private static final String ARM3_ADVANCED = "advanced:llm-cafd";

    private final MetricCollector collector = new MetricCollector();

    /**
     * Runs the pointer analysis for {@code config} on {@code mainClass} found
     * on {@code benchmarkCp}, returns the collected metrics plus timing,
     * memory, cost, and LLM query count.
     *
     * @param config      the analysis configuration (baseline or arm)
     * @param benchmarkCp classpath directory containing the benchmark's classes
     * @param mainClass   simple or fully-qualified main class name
     * @return result snapshot for this run
     */
    public RunResult run(Configs.Config config,
                         String benchmarkCp,
                         String mainClass) {
        boolean isArm1 = isArm1(config);
        boolean isArm2 = config.ptaArgs().contains(ARM2_PLUGIN);
        boolean isArm3 = isArm3(config);

        CountingOracle arm1Counter = null;
        CountingOracle arm2Counter = null;
        CountingOracle arm3Counter = null;

        boolean arm1InjectedHere = false;
        boolean arm2InjectedHere = false;
        boolean arm3InjectedHere = false;

        // ── Arm ① injection ──────────────────────────────────────────────
        // arm1.ArmOracleFactory.fromOptions handles the oracle at analysis time;
        // to count queries we must inject a CountingOracle BEFORE the run.
        // We only inject when no external override (e.g. RobustnessSweep) is set.
        if (isArm1 && pta.arm1.ArmOracleFactory.oracleOverride == null) {
            LlmOracle base = buildArm1BaseOracle(config);
            arm1Counter = new CountingOracle(base);
            pta.arm1.ArmOracleFactory.setOracle(arm1Counter);
            arm1InjectedHere = true;
        }

        // ── Arm ② injection ──────────────────────────────────────────────
        if (isArm2 && !LlmReflectionModel.hasOracle()) {
            LlmOracle base = buildArm2BaseOracle(config);
            arm2Counter = new CountingOracle(base);
            LlmReflectionModel.setOracle(arm2Counter);
            arm2InjectedHere = true;
        } else if (isArm2) {
            logger.info("[ConfigRunner] A2 external oracle already set; skipping own injection");
        }

        // ── Arm ③ injection ──────────────────────────────────────────────
        if (isArm3 && pta.arm3.ArmOracleFactory.oracleOverride == null) {
            LlmOracle base = buildArm3BaseOracle(config);
            arm3Counter = new CountingOracle(base);
            pta.arm3.ArmOracleFactory.setOracle(arm3Counter);
            arm3InjectedHere = true;
        }

        try {
            String[] args = buildMainArgs(config, benchmarkCp, mainClass);
            logger.info("[ConfigRunner] {} / {} / {}", config.id(), benchmarkCp, mainClass);

            long t0 = System.nanoTime();
            Main.main(args);
            long timeMs = (System.nanoTime() - t0) / 1_000_000L;

            Runtime rt = Runtime.getRuntime();
            long memMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);

            PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
            MetricCollector.Metrics m = collector.collect(result);

            // Pick up counts from whichever counter was active.
            long queries = 0;
            double cost = 0.0;
            if (arm1Counter != null) {
                queries = arm1Counter.queryCount();
                cost = arm1Counter.totalCostUsd();
            } else if (arm2Counter != null) {
                queries = arm2Counter.queryCount();
                cost = arm2Counter.totalCostUsd();
            } else if (arm3Counter != null) {
                queries = arm3Counter.queryCount();
                cost = arm3Counter.totalCostUsd();
            }

            logger.info("[ConfigRunner] {} done in {}ms mem={}MB llmQ={} cost=${}",
                    config.id(), timeMs, memMb, queries, cost);
            return new RunResult(m, timeMs, memMb, cost, queries);

        } finally {
            if (arm1InjectedHere) {
                pta.arm1.ArmOracleFactory.clearOracle();
            }
            if (arm2InjectedHere) {
                LlmReflectionModel.clearOracle();
            }
            if (arm3InjectedHere) {
                pta.arm3.ArmOracleFactory.clearOracle();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Arm detection
    // -----------------------------------------------------------------------

    private static boolean isArm1(Configs.Config config) {
        // advanced:llm but NOT advanced:llm-cafd (arm③)
        return config.ptaArgs().contains(ARM1_ADVANCED)
                && !config.ptaArgs().contains(ARM3_ADVANCED);
    }

    private static boolean isArm3(Configs.Config config) {
        return config.ptaArgs().contains(ARM3_ADVANCED);
    }

    // -----------------------------------------------------------------------
    // Base oracle builders (before CountingOracle wrapping)
    // -----------------------------------------------------------------------

    /** Builds the base (unwrapped) oracle for arm①. */
    private static LlmOracle buildArm1BaseOracle(Configs.Config config) {
        String mockFile = extractOption(config.ptaArgs(), "llm-mock-file");
        if (mockFile != null && !mockFile.isBlank()) {
            logger.info("[ConfigRunner] A1 base oracle: MockOracle from {}", mockFile);
            return ArmOracleFactory.loadMockOracle(Path.of(mockFile));
        }
        // No mock: no-op oracle (arm① will fall back to its own fromOptions path,
        // but since we've overridden it, return a no-op that answers NO to all)
        logger.info("[ConfigRunner] A1 base oracle: no-op (no llm-mock-file)");
        return new pta.llm.MockOracle(java.util.Map.of(), "NO");
    }

    /** Builds the base (unwrapped) oracle for arm②. */
    private static LlmOracle buildArm2BaseOracle(Configs.Config config) {
        String mockFile = extractOption(config.ptaArgs(), "llm-mock-file");
        if (mockFile != null && !mockFile.isBlank()) {
            logger.info("[ConfigRunner] A2 base oracle: MockOracle from {}", mockFile);
            return ArmOracleFactory.loadMockOracle(Path.of(mockFile));
        }
        logger.info("[ConfigRunner] A2 base oracle: no-op (no llm-mock-file)");
        return new pta.llm.MockOracle(java.util.Map.of(), "");
    }

    /** Builds the base (unwrapped) oracle for arm③. */
    private static LlmOracle buildArm3BaseOracle(Configs.Config config) {
        String mockFile = extractOption(config.ptaArgs(), "llm-mock-file");
        if (mockFile != null && !mockFile.isBlank()) {
            logger.info("[ConfigRunner] A3 base oracle: MockOracle from {}", mockFile);
            return ArmOracleFactory.loadMockOracle(Path.of(mockFile));
        }
        logger.info("[ConfigRunner] A3 base oracle: no-op (no llm-mock-file)");
        return new pta.llm.MockOracle(java.util.Map.of(), "NO");
    }

    // -----------------------------------------------------------------------
    // Arg construction
    // -----------------------------------------------------------------------

    private String[] buildMainArgs(Configs.Config config,
                                   String benchmarkCp,
                                   String mainClass) {
        List<String> args = new ArrayList<>();
        args.add("-cp");
        args.add(benchmarkCp);
        args.add("-m");
        args.add(mainClass);
        args.add("-a");
        args.add(PointerAnalysis.ID + "=" + mergePtaArgs(config.ptaArgs()));
        return args.toArray(new String[0]);
    }

    private static String mergePtaArgs(String configPtaArgs) {
        List<String> result = new ArrayList<>(BASE_OPTS);
        List<String> plugins = new ArrayList<>();

        for (String token : configPtaArgs.split(";")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("plugins:[")) {
                int lb = token.indexOf('[');
                int rb = token.indexOf(']');
                if (lb >= 0 && rb > lb) {
                    String inner = token.substring(lb + 1, rb);
                    for (String cls : inner.split(",")) {
                        String c = cls.trim();
                        if (!c.isEmpty()) {
                            plugins.add(c);
                        }
                    }
                }
            } else {
                result.add(token);
            }
        }

        if (!plugins.isEmpty()) {
            result.add("plugins:[" + String.join(",", plugins) + "]");
        }

        return String.join(";", result);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String extractOption(String ptaArgs, String key) {
        String prefix = key + ":";
        for (String token : ptaArgs.split(";")) {
            token = token.trim();
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }
}
```

**IMPORTANT NOTE on `pta.arm1.ArmOracleFactory.oracleOverride` and `pta.arm3.ArmOracleFactory.oracleOverride`:**
These fields are `private static volatile` in the current code. The ConfigRunner above accesses them directly, which won't compile because they're private. Instead, add a package-level visibility check:

**Alternative approach that avoids direct field access:** Add a `hasOracle()` method to `arm1.ArmOracleFactory` and `arm3.ArmOracleFactory` mirroring what `arm2.LlmReflectionModel` already has:

In `$REPO/src/main/java/pta/arm1/ArmOracleFactory.java`, add after `clearOracle()`:
```java
    /** Returns {@code true} when a static oracle override is currently installed. */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }
```

In `$REPO/src/main/java/pta/arm3/ArmOracleFactory.java`, add after `clearOracle()`:
```java
    /** Returns {@code true} when a static oracle override is currently installed. */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }
```

Then in ConfigRunner, replace `pta.arm1.ArmOracleFactory.oracleOverride == null` with `!pta.arm1.ArmOracleFactory.hasOracle()` and `pta.arm3.ArmOracleFactory.oracleOverride == null` with `!pta.arm3.ArmOracleFactory.hasOracle()`.

The corrected ConfigRunner checks become:
```java
        if (isArm1 && !pta.arm1.ArmOracleFactory.hasOracle()) {
```
and:
```java
        if (isArm3 && !pta.arm3.ArmOracleFactory.hasOracle()) {
```

- [ ] **Step 4: Add `hasOracle()` to arm1 and arm3 ArmOracleFactory**

In `$REPO/src/main/java/pta/arm1/ArmOracleFactory.java`, after the `clearOracle()` method (after line 81):
```java
    /** Returns {@code true} when a static oracle override is currently installed. */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }
```

In `$REPO/src/main/java/pta/arm3/ArmOracleFactory.java`, after the `clearOracle()` method (after line 48):
```java
    /** Returns {@code true} when a static oracle override is currently installed. */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }
```

- [ ] **Step 5: Run tests to verify new ConfigRunnerTest passes**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.eval.ConfigRunnerTest' 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 6: Run full suite to confirm no regressions**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add \
  src/main/java/pta/eval/ConfigRunner.java \
  src/test/java/pta/eval/ConfigRunnerTest.java \
  src/main/java/pta/arm1/ArmOracleFactory.java \
  src/main/java/pta/arm3/ArmOracleFactory.java
git commit -m "feat(eval): surface timeMs/costUsd/llmQueries in ConfigRunner.RunResult"
```

---

### Task 4: Part B3 — MetricCollector CSV gets costUsd/llmQueries columns

**Files:**
- Modify: `$REPO/src/main/java/pta/eval/MetricCollector.java`
- Modify: `$REPO/src/test/java/pta/eval/MetricCollectorTest.java`

**Interfaces:**
- Modifies: `Metrics.toCsvRow(config, benchmark, timeMs, memMb, costUsd, llmQueries)` — 2 new params
- Modifies: `Metrics.CSV_HEADER` — adds `costUsd,llmQueries` at the end
- Column count: 10 → 12

- [ ] **Step 1: Update MetricCollectorTest to expect 12 columns**

In `$REPO/src/test/java/pta/eval/MetricCollectorTest.java`:

Change `EXPECTED_CSV_COLUMNS` from `10` to `12`:
```java
    /** CSV column count: config, benchmark, timeMs, memMb + 6 metric fields + costUsd + llmQueries. */
    private static final int EXPECTED_CSV_COLUMNS = 12;
```

Change the `csvHeaderHasCorrectColumnsInOrder` test to also assert new columns:
```java
    /** CSV_HEADER must list 12 column names in the prescribed order. */
    @Test
    void csvHeaderHasCorrectColumnsInOrder() {
        String header = MetricCollector.Metrics.CSV_HEADER;
        String[] cols = header.split(",", -1);
        assertEquals(EXPECTED_CSV_COLUMNS, cols.length,
                "CSV_HEADER must have exactly " + EXPECTED_CSV_COLUMNS + " columns");
        assertEquals("config", cols[0]);
        assertEquals("benchmark", cols[1]);
        assertEquals("timeMs", cols[2]);
        assertEquals("memMb", cols[3]);
        assertEquals("mayFailCasts", cols[4]);
        assertEquals("avgPtsSize", cols[5]);
        assertEquals("polyCallSites", cols[6]);
        assertEquals("reachableMethods", cols[7]);
        assertEquals("aliasPairs", cols[8]);
        assertEquals("objects", cols[9]);
        assertEquals("costUsd", cols[10]);
        assertEquals("llmQueries", cols[11]);
    }
```

And update the CSV row shape test call and column count:
```java
        // CSV row shape: pass 0.0 costUsd and 0 llmQueries for a baseline run
        String csv = m.toCsvRow("ci", "Dispatch", 100L, 64L, 0.0, 0L);
        String[] parts = csv.split(",", -1);
        assertEquals(EXPECTED_CSV_COLUMNS, parts.length,
                "CSV row must have exactly " + EXPECTED_CSV_COLUMNS + " columns");

        // Column order: config, benchmark, timeMs, memMb, mayFailCasts, avgPtsSize,
        //               polyCallSites, reachableMethods, aliasPairs, objects, costUsd, llmQueries
        assertEquals("ci", parts[0], "column 0 must be config");
        assertEquals("Dispatch", parts[1], "column 1 must be benchmark");
        assertEquals("100", parts[2], "column 2 must be timeMs");
        assertEquals("64", parts[3], "column 3 must be memMb");
        assertEquals("0.000000", parts[10], "column 10 must be costUsd=0.000000");
        assertEquals("0", parts[11], "column 11 must be llmQueries=0");
```

- [ ] **Step 2: Run tests to verify they fail (toCsvRow still has old signature)**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.eval.MetricCollectorTest' 2>&1 | tail -20
```
Expected: compile error.

- [ ] **Step 3: Update MetricCollector.java — extend toCsvRow and CSV_HEADER**

In `$REPO/src/main/java/pta/eval/MetricCollector.java`:

Change `CSV_HEADER`:
```java
        public static final String CSV_HEADER =
                "config,benchmark,timeMs,memMb,"
                + "mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects,"
                + "costUsd,llmQueries";
```

Change `toCsvRow` signature and body:
```java
        /**
         * Returns a single CSV data row for this metric snapshot.
         *
         * @param config     analysis configuration label
         * @param benchmark  benchmark/program name
         * @param timeMs     wall-clock analysis time in milliseconds
         * @param memMb      peak heap usage in megabytes
         * @param costUsd    estimated LLM cost in USD (0 for baselines)
         * @param llmQueries number of LLM oracle calls made (0 for baselines)
         */
        public String toCsvRow(String config, String benchmark,
                                long timeMs, long memMb,
                                double costUsd, long llmQueries) {
            return String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.6f,%d,%d,%d,%d,%.6f,%d",
                    config, benchmark, timeMs, memMb,
                    mayFailCasts, avgPtsSize, polyCallSites,
                    reachableMethods, aliasPairs, objects,
                    costUsd, llmQueries);
        }
```

- [ ] **Step 4: Fix any callers of old `toCsvRow(config, benchmark, timeMs, memMb)` 4-arg form**

Search for existing callers (they must pass the two new args):
```bash
grep -rn "toCsvRow" /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e/src/
```
Update each call site to pass `0.0, 0L` (or the real values from `RunResult`) for `costUsd, llmQueries`. The main callers are in `RobustnessSweep.java` (if any) and any integration test that calls `toCsvRow` directly.

- [ ] **Step 5: Run MetricCollector tests**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.eval.MetricCollectorTest' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Run full suite**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add \
  src/main/java/pta/eval/MetricCollector.java \
  src/test/java/pta/eval/MetricCollectorTest.java
git commit -m "feat(eval): add costUsd and llmQueries columns to MetricCollector CSV"
```

---

### Task 5: Part B4 — report.py gets cost columns + cost_vs_precision plot

**Files:**
- Modify: `$REPO/eval/report.py`
- Modify: `$REPO/eval/test_report.py`

**Interfaces:**
- Consumes: `results.csv` with 12 columns (now includes `costUsd,llmQueries`)
- Produces: `comparison_table.md` with `costUsd,llmQueries` columns
- Produces: `cost_vs_precision.png` — scatter: x=`costUsd` (lower=cheaper), y=`avgPtsSize` (lower=more precise), one point per config

- [ ] **Step 1: Update test_report.py — add columns to canned CSV + new assertions**

In `$REPO/eval/test_report.py`:

1. Remove `import os` and `import tempfile` (unused — Part C cleanup, do it here).
2. Update `RESULTS_CSV` to add `costUsd,llmQueries` columns:

```python
RESULTS_CSV = textwrap.dedent("""\
    config,benchmark,timeMs,memMb,mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects,costUsd,llmQueries
    B0,test-bench,1000,256,50,3.200000,20,500,1000,800,0.000000,0
    B1,test-bench,1200,280,45,2.900000,18,510,950,790,0.000000,0
    B2z,test-bench,1400,300,42,2.700000,17,515,900,780,0.000000,0
    B2s,test-bench,1600,320,40,2.500000,16,520,880,770,0.000000,0
    B3,test-bench,1800,340,38,2.300000,15,525,860,760,0.000000,0
    A1,test-bench,2000,360,35,2.100000,14,530,840,750,0.001234,42
    A2,test-bench,2200,380,33,1.900000,13,535,820,740,0.000987,38
    A3,test-bench,2400,400,30,1.700000,12,540,800,730,0.000543,21
""")
```

3. Add a new test `test_cost_vs_precision_png_created` and `test_comparison_table_has_cost_and_query_columns`:

```python
    def test_cost_vs_precision_png_created(self, canned_data):
        """cost_vs_precision.png must be created and non-empty."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        png_path = pathlib.Path(canned_data["outdir"]) / "cost_vs_precision.png"
        assert png_path.exists(), "cost_vs_precision.png was not created"
        assert png_path.stat().st_size > 0, "cost_vs_precision.png is empty"

    def test_comparison_table_has_cost_and_query_columns(self, canned_data):
        """The comparison table header must contain costUsd and llmQueries columns."""
        _run_report(
            canned_data["results"],
            canned_data["robustness"],
            canned_data["outdir"],
        )
        table_path = pathlib.Path(canned_data["outdir"]) / "comparison_table.md"
        text = table_path.read_text()
        header_line = next(
            (line for line in text.splitlines() if line.strip().startswith("|")),
            None,
        )
        assert header_line is not None, "No header row found in table"
        header_cells = {c.strip() for c in header_line.strip("|").split("|")}
        for col in ("costUsd", "llmQueries"):
            assert col in header_cells, (
                f"Table header missing '{col}' column.\nHeader: {header_line}"
            )
```

4. Update the `test_comparison_table_has_required_metric_columns` assertion set to keep `required` unchanged (no regression on existing columns).

- [ ] **Step 2: Run test_report.py to verify new tests fail**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
python3 -m pytest eval/test_report.py -v 2>&1 | tail -20
```
Expected: new tests FAIL because report.py doesn't know about `costUsd`/`llmQueries` yet, and `cost_vs_precision.png` isn't produced.

- [ ] **Step 3: Update report.py**

1. Remove `import os` (unused).
2. Add `"costUsd"` and `"llmQueries"` to `TABLE_COLS`:

```python
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
    "costUsd",
    "llmQueries",
]
```

3. Add these two columns to the numeric aggregation in `_make_comparison_table`:
```python
    numeric_cols = [
        "timeMs", "memMb", "mayFailCasts", "avgPtsSize",
        "polyCallSites", "reachableMethods", "aliasPairs", "objects",
        "costUsd", "llmQueries",
    ]
```

4. Add `_make_cost_precision_plot` function:

```python
def _make_cost_precision_plot(results: pd.DataFrame, outdir: pathlib.Path) -> None:
    """
    Scatter plot: x = mean costUsd (LLM cost), y = mean avgPtsSize (precision,
    lower=better). One point per config. Shows cost as a tool-quality dimension
    alongside precision. Baselines cluster at costUsd=0.
    """
    numeric_cols = ["costUsd", "avgPtsSize"]
    agg = results.groupby("config")[numeric_cols].mean().reset_index()
    agg = _sort_configs(agg)

    fig, ax = plt.subplots(figsize=(8, 5))

    xs = agg["costUsd"].tolist()
    ys = agg["avgPtsSize"].tolist()
    labels = agg["config"].tolist()

    # Color baselines (costUsd == 0) differently
    colors = ["steelblue" if x == 0.0 else "darkorange" for x in xs]
    ax.scatter(xs, ys, s=80, zorder=3, color=colors)

    for x, y, lbl in zip(xs, ys, labels):
        ax.annotate(lbl, (x, y), textcoords="offset points",
                    xytext=(5, 4), fontsize=9)

    ax.set_xlabel("Estimated LLM cost (USD, lower=cheaper)", fontsize=11)
    ax.set_ylabel("Avg points-to set size (lower = more precise)", fontsize=11)
    ax.set_title("Precision vs. LLM Cost", fontsize=12)
    ax.grid(True, linestyle="--", alpha=0.4)

    # Legend
    from matplotlib.lines import Line2D
    legend_elements = [
        Line2D([0], [0], marker='o', color='w', markerfacecolor='steelblue',
               markersize=9, label='Baseline (no LLM cost)'),
        Line2D([0], [0], marker='o', color='w', markerfacecolor='darkorange',
               markersize=9, label='LLM-augmented arm'),
    ]
    ax.legend(handles=legend_elements, fontsize=9)

    fig.tight_layout()
    fig.savefig(outdir / "cost_vs_precision.png", dpi=150)
    plt.close(fig)
```

5. Call `_make_cost_precision_plot` from `generate_report`:
```python
    _make_cost_precision_plot(results, out)
```

6. Update the print statements to include the new output:
```python
    print(f"  cost_vs_precision.png")
```

- [ ] **Step 4: Run test_report.py to verify all pass**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
python3 -m pytest eval/test_report.py -v 2>&1 | tail -20
```
Expected: 8 tests pass (6 old + 2 new).

- [ ] **Step 5: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add eval/report.py eval/test_report.py
git commit -m "feat(eval): add costUsd/llmQueries to report table + cost-vs-precision plot"
```

---

### Task 6: Part C — Stale comment cleanup

**Files:**
- Modify: `$REPO/src/main/java/pta/eval/UnsoundCafdStylePlugin.java`
- Modify: `$REPO/src/test/java/pta/eval/RobustnessSweepTest.java`
- Modify: `$REPO/src/test/resources/pta/eval/SweepBenchmark.java`

**No interface changes — pure comment edits.**

- [ ] **Step 1: Fix UnsoundCafdStylePlugin.java stale comments**

In `$REPO/src/main/java/pta/eval/UnsoundCafdStylePlugin.java`:

Line ~111 — change:
```java
     *   <li>ConsistencyEngine rejection of contradictory never-alias facts</li>
```
to:
```java
     *   <li>LlmWrapperProposer / WrapperDetector structural confirmation (advanced:llm-cafd)</li>
```

Line ~140 — change:
```java
        // Parse proposals — NO ConsistencyEngine check (this is the unsoundness).
```
to:
```java
        // Parse proposals — NO structural check (this is the unsoundness; arm③ would
        // confirm via LlmWrapperProposer + WrapperDetector before applying).
```

Line ~213 — change:
```java
    // Helpers (mirrored from LlmFactPlugin for self-containment)
```
to:
```java
    // Helpers (self-contained; mirrors simple type-name extraction logic)
```

- [ ] **Step 2: Fix RobustnessSweepTest.java stale comments**

In `$REPO/src/test/java/pta/eval/RobustnessSweepTest.java`:

Line ~224 — change:
```java
     * fact that arm ③'s ConsistencyEngine would reject. The unsound plugin
```
to:
```java
     * fact that arm ③'s LlmWrapperProposer/WrapperDetector would reject. The unsound plugin
```

Line ~240 — change:
```java
        // Arm③ would REJECT this (ConsistencyEngine: alias(ARunner,ARunner) exists).
```
to:
```java
        // Arm③ would REJECT this (WrapperDetector: structural check fails).
```

- [ ] **Step 3: Fix SweepBenchmark.java stale comment**

In `$REPO/src/test/resources/pta/eval/SweepBenchmark.java`:

Line ~37 — change:
```java
// "never-alias ARunner ARunner" without a ConsistencyEngine check. This bans
```
to:
```java
// "never-alias ARunner ARunner" without a structural LlmWrapperProposer check. This bans
```

- [ ] **Step 4: Run full test suite**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -10
python3 -m pytest eval/test_report.py -v 2>&1 | tail -10
```
Expected: Java tests BUILD SUCCESSFUL, Python 8 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
git add \
  src/main/java/pta/eval/UnsoundCafdStylePlugin.java \
  src/test/java/pta/eval/RobustnessSweepTest.java \
  src/test/resources/pta/eval/SweepBenchmark.java
git commit -m "chore: replace stale ConsistencyEngine/LlmFactPlugin references in comments"
```

---

### Task 7: Write task-8-report.md

**Files:**
- Create: `$REPO/.superpowers/sdd/task-8-report.md`

- [ ] **Step 1: Run final test suite and capture output**

```bash
cd /home1/kaixuan/sast_study/PTA-enhancement/人工智能逻辑大会投稿/Tai-e
./gradlew :test --tests 'pta.*' 2>&1 | tail -15
python3 -m pytest eval/test_report.py -v 2>&1 | tail -15
git log --oneline -6
```

- [ ] **Step 2: Write the report**

Write `$REPO/.superpowers/sdd/task-8-report.md` with:
- What changed (Parts A/B/C summary)
- How cost/queries are surfaced per config (CountingOracle → RunResult → CSV)
- New CSV columns (costUsd, llmQueries)
- New report view (cost_vs_precision.png)
- Exact test output (pass counts)
- Commit hashes
- Any deviations from the spec

---

## Self-Review Checklist

**Spec coverage:**
- [x] Part A: `gemini-2.5-flash-lite` default in yml + arm1 factory — Task 1
- [x] Part A: Flash-Lite pricing rates `1e-7`/`4e-7` in arm1 factory — Task 1
- [x] Part B: `CountingOracle` implements `LlmOracle` — Task 2
- [x] Part B: `ConfigRunner.RunResult` record with 5 fields — Task 3
- [x] Part B: `timeMs` wired through (not discarded) — Task 3
- [x] Part B: `memMb` measured via `Runtime` — Task 3
- [x] Part B: arm① CountingOracle injection via `setOracle`/`clearOracle` — Task 3
- [x] Part B: arm③ CountingOracle injection via `setOracle`/`clearOracle` — Task 3
- [x] Part B: arm② CountingOracle wraps existing injection — Task 3
- [x] Part B: `hasOracle()` gate respected — Task 3 (arm2 existing; arm1/arm3 added)
- [x] Part B: baselines have cost=0, queries=0 — Task 3 + test
- [x] Part B: `costUsd`/`llmQueries` in CSV_HEADER and toCsvRow — Task 4
- [x] Part B: comparison_table.md includes costUsd/llmQueries — Task 5
- [x] Part B: cost_vs_precision.png added — Task 5
- [x] Part C: `import os` removed from report.py — Task 5
- [x] Part C: `import os`, `import tempfile` removed from test_report.py — Task 5
- [x] Part C: stale comments in UnsoundCafdStylePlugin fixed — Task 6
- [x] Part C: stale comments in RobustnessSweepTest fixed — Task 6
- [x] Part C: stale comment in SweepBenchmark fixed — Task 6

**Missed spec item: arm③ has no live GeminiOracle path currently (fromOptions returns null for no mock file).** For arm③ CountingOracle injection in ConfigRunner Task 3, when `mockFile` is null, a no-op MockOracle is wrapped — this is consistent with the existing arm③ behaviour (null oracle = no-op) and is correct.

**Type consistency check:**
- `CountingOracle.queryCount()` returns `long` — matches `RunResult.llmQueries` (`long`) ✓
- `CountingOracle.totalCostUsd()` returns `double` — matches `RunResult.costUsd` (`double`) ✓
- `Metrics.toCsvRow(String, String, long, long, double, long)` — matches call sites in Task 4 ✓
- `EXPECTED_CSV_COLUMNS = 12` — matches `CSV_HEADER` with 12 columns ✓

**Placeholder scan:** No TBDs, no "implement later", all code blocks are complete. ✓
