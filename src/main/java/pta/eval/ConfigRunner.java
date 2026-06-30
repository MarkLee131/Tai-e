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
        // Only inject when no external override (e.g. RobustnessSweep) is set.
        if (isArm1 && !pta.arm1.ArmOracleFactory.hasOracle()) {
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
        if (isArm3 && !pta.arm3.ArmOracleFactory.hasOracle()) {
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
