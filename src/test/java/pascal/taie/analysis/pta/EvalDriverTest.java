/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pta.eval.ConfigRunner;
import pta.eval.Configs;
import pta.eval.MetricCollector;
import pta.eval.RobustnessSweep;
import pta.llm.ApiKeyResolver;
import pta.llm.CostMeter;
import pta.llm.CountingOracle;
import pta.llm.GeminiOracle;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;
import pta.llm.MockOracle;
import pta.llm.PromptCache;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * End-to-end OFFLINE evaluation driver — the glue that produces the two CSVs
 * consumed by {@code eval/report.py}. Runs entirely with mock oracles, so it
 * makes ZERO live API calls (cost = $0) and is fully deterministic.
 *
 * <p>Gated by {@code -Deval.run=true} (skipped in the normal {@code test}
 * task). Invoke via the {@code runEval} Gradle task. Writes to {@code eval/out/}:
 * <ul>
 *   <li>{@code robustness.csv}        — Deliverable 1: thesis robustness sweep
 *       (arm① / arm③ sound vs CAFD-style unsound) on the controlled benchmark.</li>
 *   <li>{@code results_dacapo.csv}    — Deliverable 2: baselines B0/B1/B2z/B2s/B3
 *       on real DaCapo-2006 programs (no LLM; real precision/time/cost spread).</li>
 *   <li>{@code results_controlled.csv}— Deliverable 3: all configs incl. arms on
 *       the controlled benchmark (pipeline validation; cost/#queries columns).</li>
 * </ul>
 *
 * <p>The two {@code results_*.csv} files share the {@link MetricCollector}
 * schema; {@code robustness.csv} follows the {@link RobustnessSweep} schema that
 * {@code report.py} expects.
 */
public class EvalDriverTest {

    private static final String OUT_DIR = "eval/out";
    private static final String BENCHMARK_HOME = "java-benchmarks";

    // Controlled benchmark (test resources) — comparable single-program table.
    private static final String CTRL_CP = "src/test/resources/pta/eval";
    private static final String SWEEP_MAIN = "SweepBenchmark";
    private static final String ARM3_MAIN = "Arm3Sweep";
    private static final String ARM1_MOCK = CTRL_CP + "/sweep-arm1-allyes.txt";
    private static final String ARM3_MOCK = CTRL_CP + "/arm3-sweep-good.txt";

    /** Per-run wall-clock cap for DaCapo analyses (seconds). */
    private static final int DACAPO_TIME_LIMIT_SEC = 600;

    @Test
    void runFullEval() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("eval.run"),
                "Eval driver disabled — set -Deval.run=true (Gradle task runEval) to enable");

        Path out = Path.of(OUT_DIR);
        Files.createDirectories(out);

        // Deliverable 1 — robustness sweep (fast, highest-value thesis figure).
        writeRobustnessCsv(out.resolve("robustness.csv"));

        // Deliverable 3 — controlled all-config table (fast; proves arm wiring).
        writeControlledResultsCsv(out.resolve("results_controlled.csv"));

        // Deliverable 2 — DaCapo baseline spread (slower; real numbers).
        writeDaCapoResultsCsv(out.resolve("results_dacapo.csv"));

        // Deliverable 4 — LIVE arms (real Gemini calls; opt-in, costs $).
        writeLiveResultsCsv(out.resolve("results_live.csv"));

        System.out.println("[eval] all CSVs written to " + out.toAbsolutePath());
    }

    // =======================================================================
    // Deliverable 4: results_live.csv — arms with a REAL Gemini oracle
    // =======================================================================
    //
    // Opt-in via -Deval.live=true (the Gradle `runEval -PevalLive` flag). This
    // makes real API calls; cost is metered and capped at $100 by CostMeter, and
    // every prompt is disk-cached so reruns are free. Arm① and arm③ are run live
    // on the controlled benchmark (cheap proof) and, if -Deval.liveDaCapo=true,
    // on the DaCapo set too. ConfigRunner cannot do this (it forces a mock when no
    // mock file is set), so we drive Main.main directly and inject a
    // CountingOracle(live GeminiOracle) as each arm's static override.

    /** Arm identifiers for the live runner. */
    private enum LiveArm { A1, A3 }

    private void writeLiveResultsCsv(Path file) throws IOException {
        if (!Boolean.getBoolean("eval.live")) {
            System.out.println("\n[eval] live arms skipped (set -Deval.live=true to enable)");
            return;
        }
        System.out.println("\n[eval] === Deliverable 4: LIVE arms (real Gemini) ===");
        String key = ApiKeyResolver.resolve();
        if (key.isEmpty()) {
            System.out.println("[eval]   no API key resolved — skipping live phase");
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(MetricCollector.Metrics.CSV_HEADER);

        // Controlled benchmark proof (a handful of queries, cents).
        runLiveControlled(lines, "A1-live", LiveArm.A1, SWEEP_MAIN, "sweep");
        runLiveControlled(lines, "A3-live", LiveArm.A3, ARM3_MAIN, "arm3sweep");
        Files.write(file, lines, StandardCharsets.UTF_8);

        // DaCapo live (slower, sequential network calls; opt-in).
        if (Boolean.getBoolean("eval.liveDaCapo")) {
            Map<String, BenchmarkInfo> all =
                    BenchmarkInfo.load(BENCHMARK_HOME + "/benchmark-info.yml");
            String listProp = System.getProperty("eval.dacapo", "luindex,antlr");
            for (String benchId : listProp.split(",")) {
                benchId = benchId.trim();
                BenchmarkInfo info = all.get(benchId);
                if (info == null) {
                    continue;
                }
                String appCp = buildClassPath(info.apps());
                String libCp = buildClassPath(info.libs());
                String reflLog = new File(BENCHMARK_HOME, info.reflectionLog()).toString();
                System.out.printf("%n[eval] --- LIVE DaCapo %s ---%n", benchId);
                runLiveDaCapo(lines, "A1-live", LiveArm.A1, info, appCp, libCp, reflLog, benchId);
                Files.write(file, lines, StandardCharsets.UTF_8);
                runLiveDaCapo(lines, "A3-live", LiveArm.A3, info, appCp, libCp, reflLog, benchId);
                Files.write(file, lines, StandardCharsets.UTF_8);
            }
        }
        System.out.println("[eval] wrote " + file + " (" + (lines.size() - 1) + " rows)");
    }

    /**
     * Live model, overridable with {@code -Deval.model=...}. Default is
     * {@code gemini-2.5-flash} (gemini-2.5-flash-lite was server-overloaded with
     * 503s during testing; 2.5-flash answered in ~1s). Token rates below are the
     * approximate 2.5-flash price ($0.30/1M in, $2.50/1M out); cost is tiny and
     * hard-capped at $100 regardless.
     */
    private static final String LIVE_MODEL =
            System.getProperty("eval.model", "gemini-2.5-flash");

    /** Builds a fresh CountingOracle wrapping a live GeminiOracle (shared disk cache). */
    private static CountingOracle liveCounter() {
        GeminiOracle base = new GeminiOracle(LIVE_MODEL,
                ApiKeyResolver.resolve(),
                new PromptCache(Path.of(".llm-cache")),
                new CostMeter(100.0, 3e-7, 2.5e-6));
        return new CountingOracle(base);
    }

    private static void injectLive(LiveArm arm, LlmOracle oracle) {
        switch (arm) {
            case A1 -> pta.arm1.ArmOracleFactory.setOracle(oracle);
            case A3 -> pta.arm3.ArmOracleFactory.setOracle(oracle);
        }
    }

    private static void clearLive(LiveArm arm) {
        switch (arm) {
            case A1 -> pta.arm1.ArmOracleFactory.clearOracle();
            case A3 -> pta.arm3.ArmOracleFactory.clearOracle();
        }
    }

    /** PTA advanced strategy string for each live arm. */
    private static String advancedFor(LiveArm arm) {
        return arm == LiveArm.A1 ? "llm" : "llm-cafd";
    }

    /** Context-sensitivity for each live arm (① layers on 2-obj; ③ on CI like B3). */
    private static String csFor(LiveArm arm) {
        return arm == LiveArm.A1 ? "2-obj" : "ci";
    }

    private void runLiveControlled(List<String> lines, String id, LiveArm arm,
                                   String main, String benchName) {
        CountingOracle counter = liveCounter();
        injectLive(arm, counter);
        try {
            String pta = "implicit-entries:false;only-app:true;distinguish-string-constants:all"
                    + ";cs:" + csFor(arm) + ";advanced:" + advancedFor(arm);
            String[] args = {"-cp", CTRL_CP, "-m", main, "-a", "pta=" + pta};
            long t0 = System.nanoTime();
            Main.main(args);
            long timeMs = (System.nanoTime() - t0) / 1_000_000L;
            Runtime rt = Runtime.getRuntime();
            long memMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            MetricCollector.Metrics m = new MetricCollector()
                    .collect(World.get().getResult(PointerAnalysis.ID));
            lines.add(m.toCsvRow(id, benchName, timeMs, memMb,
                    counter.totalCostUsd(), counter.queryCount()));
            System.out.printf("[eval]   %s on %-10s avgPts=%.3f q=%d cost=$%.6f%n",
                    id, benchName, m.avgPtsSize(), counter.queryCount(), counter.totalCostUsd());
        } catch (Throwable t) {
            System.out.printf("[eval]   %s FAILED: %s: %s%n",
                    id, t.getClass().getSimpleName(), t.getMessage());
        } finally {
            clearLive(arm);
        }
    }

    private void runLiveDaCapo(List<String> lines, String id, LiveArm arm,
                               BenchmarkInfo info, String appCp, String libCp,
                               String reflLog, String benchId) {
        CountingOracle counter = liveCounter();
        injectLive(arm, counter);
        try {
            DaCapoConfig cfg = new DaCapoConfig(id, csFor(arm), advancedFor(arm));
            String[] args = buildDaCapoArgs(info, appCp, libCp, reflLog, cfg);
            long t0 = System.nanoTime();
            Main.main(args);
            long timeMs = (System.nanoTime() - t0) / 1_000_000L;
            Runtime rt = Runtime.getRuntime();
            long memMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            MetricCollector.Metrics m = new MetricCollector()
                    .collect(World.get().getResult(PointerAnalysis.ID));
            lines.add(m.toCsvRow(id, benchId, timeMs, memMb,
                    counter.totalCostUsd(), counter.queryCount()));
            System.out.printf("[eval]   %s on %-8s avgPts=%.3f casts=%d q=%d cost=$%.6f time=%dms%n",
                    id, benchId, m.avgPtsSize(), m.mayFailCasts(),
                    counter.queryCount(), counter.totalCostUsd(), timeMs);
        } catch (Throwable t) {
            System.out.printf("[eval]   %s on %s FAILED: %s: %s%n",
                    id, benchId, t.getClass().getSimpleName(), t.getMessage());
        } finally {
            clearLive(arm);
        }
    }

    // =======================================================================
    // Deliverable 1: robustness.csv
    // =======================================================================

    private void writeRobustnessCsv(Path file) throws IOException {
        System.out.println("\n[eval] === Deliverable 1: robustness sweep ===");
        List<String> lines = new ArrayList<>();
        lines.add("config,errorRate,recall,"
                + "mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects");

        // Arm ① (sound selective-CS): YES↔NO corruption; recall stays 1.0.
        RobustnessSweep sweep = new RobustnessSweep();
        {
            Configs.Config arm1 = Configs.a1(ARM1_MOCK);
            LlmOracle base = pta.arm1.ArmOracleFactory.loadMockOracle(Path.of(ARM1_MOCK));
            Function<LlmResponse, String> corrupt = flipYesNo();
            for (RobustnessSweep.SweepPoint pt : sweep.run(
                    arm1, RobustnessSweep.DEFAULT_ERROR_RATES,
                    CTRL_CP, SWEEP_MAIN, base, corrupt)) {
                lines.add(sweepRow("A1-sound", pt));
            }
        }

        // Arm ③ (sound heap cloning): per-method YES↔NO; recall stays 1.0.
        {
            Configs.Config arm3 = Configs.a3(ARM3_MOCK);
            LlmOracle base = new MockOracle(Map.of("make", "YES"), "NO");
            Function<LlmResponse, String> corrupt = flipYesNo();
            for (RobustnessSweep.SweepPoint pt : sweep.run(
                    arm3, RobustnessSweep.DEFAULT_ERROR_RATES,
                    CTRL_CP, ARM3_MAIN, base, corrupt)) {
                lines.add(sweepRow("A3-sound", pt));
            }
        }

        // CAFD-style UNSOUND contrast: corrupt fact applied without a sound check.
        {
            Configs.Config unsound = RobustnessSweep.unsoundCafdConfig();
            LlmOracle base = new MockOracle(Map.of(), "");
            Function<LlmResponse, String> corrupt =
                    r -> "wrapper id\nnever-alias ARunner ARunner";
            for (RobustnessSweep.SweepPoint pt : sweep.run(
                    unsound, RobustnessSweep.DEFAULT_ERROR_RATES,
                    CTRL_CP, SWEEP_MAIN, base, corrupt)) {
                lines.add(sweepRow("CAFD-unsound", pt));
            }
        }

        Files.write(file, lines, StandardCharsets.UTF_8);
        System.out.println("[eval] wrote " + file + " (" + (lines.size() - 1) + " rows)");
    }

    private static String sweepRow(String config, RobustnessSweep.SweepPoint pt) {
        MetricCollector.Metrics m = pt.m();
        return String.format(java.util.Locale.ROOT,
                "%s,%.2f,%.6f,%d,%.6f,%d,%d,%d,%d",
                config, pt.errorRate(), pt.recall(),
                m.mayFailCasts(), m.avgPtsSize(), m.polyCallSites(),
                m.reachableMethods(), m.aliasPairs(), m.objects());
    }

    private static Function<LlmResponse, String> flipYesNo() {
        return r -> {
            List<String> ls = r.asLines();
            return (!ls.isEmpty() && ls.get(0).equalsIgnoreCase("YES")) ? "NO" : "YES";
        };
    }

    // =======================================================================
    // Deliverable 3: results_controlled.csv (all configs on controlled bench)
    // =======================================================================

    private void writeControlledResultsCsv(Path file) throws IOException {
        System.out.println("\n[eval] === Deliverable 3: controlled all-config table ===");
        ConfigRunner runner = new ConfigRunner();
        List<String> lines = new ArrayList<>();
        lines.add(MetricCollector.Metrics.CSV_HEADER);

        // Baselines + arm① all on SweepBenchmark (comparable single program).
        record Cell(Configs.Config cfg, String main, String benchName) {}
        List<Cell> cells = List.of(
                new Cell(Configs.B0, SWEEP_MAIN, "sweep"),
                new Cell(Configs.B1, SWEEP_MAIN, "sweep"),
                new Cell(Configs.B2z, SWEEP_MAIN, "sweep"),
                new Cell(Configs.B2s, SWEEP_MAIN, "sweep"),
                new Cell(Configs.B3, SWEEP_MAIN, "sweep"),
                new Cell(Configs.a1(ARM1_MOCK), SWEEP_MAIN, "sweep"),
                // Arm③ on its dedicated benchmark where a genuine wrapper exists.
                new Cell(Configs.a3(ARM3_MOCK), ARM3_MAIN, "arm3sweep"));

        for (Cell c : cells) {
            try {
                ConfigRunner.RunResult r = runner.run(c.cfg(), CTRL_CP, c.main());
                lines.add(r.metrics().toCsvRow(
                        c.cfg().id(), c.benchName(),
                        r.timeMs(), r.memMb(), r.costUsd(), r.llmQueries()));
                System.out.printf("[eval]   %-3s on %-10s avgPts=%.3f time=%dms q=%d%n",
                        c.cfg().id(), c.benchName(),
                        r.metrics().avgPtsSize(), r.timeMs(), r.llmQueries());
            } catch (Throwable t) {
                System.out.printf("[eval]   %-3s FAILED: %s: %s%n",
                        c.cfg().id(), t.getClass().getSimpleName(), t.getMessage());
            }
        }

        Files.write(file, lines, StandardCharsets.UTF_8);
        System.out.println("[eval] wrote " + file + " (" + (lines.size() - 1) + " rows)");
    }

    // =======================================================================
    // Deliverable 2: results_dacapo.csv (real DaCapo baselines)
    // =======================================================================

    /** A DaCapo baseline config: label + (cs, advanced) options. */
    private record DaCapoConfig(String id, String cs, String advanced) {}

    private static final List<DaCapoConfig> DACAPO_CONFIGS = List.of(
            new DaCapoConfig("B0", "ci", "null"),       // context-insensitive
            new DaCapoConfig("B1", "2-obj", "null"),    // full 2-object
            new DaCapoConfig("B2z", "2-obj", "zipper"), // selective via Zipper
            new DaCapoConfig("B2s", "2-obj", "scaler"), // selective via Scaler
            new DaCapoConfig("B3", "ci", "cafd"));      // CAFD allocator abstraction

    private void writeDaCapoResultsCsv(Path file) throws IOException {
        System.out.println("\n[eval] === Deliverable 2: DaCapo baseline spread ===");
        Map<String, BenchmarkInfo> all =
                BenchmarkInfo.load(BENCHMARK_HOME + "/benchmark-info.yml");
        String listProp = System.getProperty("eval.dacapo", "luindex,antlr");
        List<String> benchmarks = Arrays.asList(listProp.split(","));

        MetricCollector collector = new MetricCollector();
        List<String> lines = new ArrayList<>();
        lines.add(MetricCollector.Metrics.CSV_HEADER);

        for (String benchId : benchmarks) {
            benchId = benchId.trim();
            BenchmarkInfo info = all.get(benchId);
            if (info == null) {
                System.out.printf("[eval]   [WARN] benchmark not in yml: %s%n", benchId);
                continue;
            }
            String appCp = buildClassPath(info.apps());
            String libCp = buildClassPath(info.libs());
            String reflLog = new File(BENCHMARK_HOME, info.reflectionLog()).toString();
            System.out.printf("%n[eval] --- DaCapo %s (jdk=%d, main=%s) ---%n",
                    benchId, info.jdk(), info.main());

            for (DaCapoConfig cfg : DACAPO_CONFIGS) {
                try {
                    String[] args = buildDaCapoArgs(info, appCp, libCp, reflLog, cfg);
                    long t0 = System.nanoTime();
                    Main.main(args);
                    long timeMs = (System.nanoTime() - t0) / 1_000_000L;
                    Runtime rt = Runtime.getRuntime();
                    long memMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);

                    PointerAnalysisResult result =
                            World.get().getResult(PointerAnalysis.ID);
                    MetricCollector.Metrics m = collector.collect(result);
                    lines.add(m.toCsvRow(cfg.id(), benchId, timeMs, memMb, 0.0, 0L));
                    System.out.printf("[eval]   %-3s avgPts=%.3f casts=%d poly=%d "
                                    + "reach=%d time=%dms%n",
                            cfg.id(), m.avgPtsSize(), m.mayFailCasts(),
                            m.polyCallSites(), m.reachableMethods(), timeMs);
                } catch (Throwable t) {
                    System.out.printf("[eval]   %-3s on %s FAILED: %s: %s%n",
                            cfg.id(), benchId, t.getClass().getSimpleName(), t.getMessage());
                }
                // Flush incrementally so partial results survive a later crash.
                Files.write(file, lines, StandardCharsets.UTF_8);
            }
        }
        System.out.println("[eval] wrote " + file + " (" + (lines.size() - 1) + " rows)");
    }

    private static String[] buildDaCapoArgs(BenchmarkInfo info, String appCp,
                                            String libCp, String reflLog,
                                            DaCapoConfig cfg) {
        List<String> args = new ArrayList<>();
        args.addAll(Arrays.asList("-java", String.valueOf(info.jdk())));
        args.addAll(Arrays.asList("-acp", appCp));
        if (libCp != null && !libCp.isEmpty()) {
            args.addAll(Arrays.asList("-cp", libCp));
        }
        args.addAll(Arrays.asList("-m", info.main()));

        StringBuilder pta = new StringBuilder();
        pta.append("distinguish-string-constants:null")
           .append(";merge-string-objects:false")
           .append(";only-app:true")          // bound whole-program blowup
           .append(";cs:").append(cfg.cs())
           .append(";advanced:").append(cfg.advanced())
           .append(";reflection-inference:null")
           .append(";reflection-log:").append(reflLog)
           .append(";time-limit:").append(DACAPO_TIME_LIMIT_SEC);
        args.addAll(Arrays.asList("-a", "pta=" + pta));
        return args.toArray(new String[0]);
    }

    private static String buildClassPath(List<String> paths) {
        List<String> resolved = new ArrayList<>();
        for (String p : paths) {
            File f = new File(BENCHMARK_HOME, p);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                resolved.add(f.getPath());
            } else if (f.isDirectory()) {
                resolved.add(f.getPath());
                File[] children = f.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isFile() && child.getName().endsWith(".jar")) {
                            resolved.add(child.getPath());
                        }
                    }
                }
            }
        }
        return String.join(File.pathSeparator, resolved);
    }
}
