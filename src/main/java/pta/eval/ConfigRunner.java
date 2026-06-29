package pta.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pta.arm1.ArmOracleFactory;
import pta.arm2.LlmReflectionModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process runner that executes a single {@link Configs.Config} on a given
 * benchmark (classpath + main class), times it, and returns a
 * {@link MetricCollector.Metrics} snapshot.
 *
 * <h2>World reset between runs</h2>
 * <p>Each call to {@link #run} delegates to {@code pascal.taie.Main.main},
 * which reinitializes {@code pascal.taie.World} before building the new
 * analysis (via {@code WorldBuilder.build}). Callers need no extra cleanup —
 * each run starts from a fresh {@code World}.
 *
 * <h2>Timing</h2>
 * <p>Wall-clock time is measured with {@code System.nanoTime()} around the
 * {@code Main.main} call and converted to milliseconds. Memory ({@code memMb})
 * is approximated as 0; consumers of
 * {@link MetricCollector.Metrics#toCsvRow} should treat it as "not measured".
 *
 * <h2>Arm ② special handling</h2>
 * <p>{@link pta.arm2.LlmReflectionModel} uses a <em>static</em> oracle field
 * that must be populated before {@code Main.main} is called (it is consumed in
 * {@code setSolver}, which runs during the solver initialisation phase).
 * When a config's {@code ptaArgs} string contains
 * {@code pta.arm2.LlmReflectionModel}, the runner:
 * <ol>
 *   <li>Extracts the {@code llm-mock-file} path from {@code ptaArgs}.</li>
 *   <li>Loads a {@link pta.llm.MockOracle} from that file via
 *       {@link ArmOracleFactory#loadMockOracle}.</li>
 *   <li>Calls {@link LlmReflectionModel#setOracle} before the run.</li>
 *   <li>Calls {@link LlmReflectionModel#clearOracle} in a {@code finally}
 *       block to prevent oracle leakage across runs.</li>
 * </ol>
 */
public final class ConfigRunner {

    private static final Logger logger = LoggerFactory.getLogger(ConfigRunner.class);

    /**
     * Fixed PTA options prepended to every config's {@code ptaArgs}.
     * Mirror what {@code pascal.taie.analysis.Tests.getPTAArgs} sets by default.
     */
    private static final List<String> BASE_OPTS = List.of(
            "implicit-entries:false",
            "only-app:true",
            "distinguish-string-constants:all");

    private static final String ARM2_PLUGIN = "pta.arm2.LlmReflectionModel";

    private final MetricCollector collector = new MetricCollector();

    /**
     * Runs the pointer analysis for {@code config} on {@code mainClass} found
     * on {@code benchmarkCp}, returns the collected metrics.
     *
     * @param config      the analysis configuration (baseline or arm)
     * @param benchmarkCp classpath directory containing the benchmark's source/classes
     * @param mainClass   simple or fully-qualified main class name
     * @return metric snapshot for this run
     * @throws RuntimeException if the analysis fails (propagated from
     *                          {@code Main.main})
     */
    public MetricCollector.Metrics run(Configs.Config config,
                                       String benchmarkCp,
                                       String mainClass) {
        boolean arm2Active = config.ptaArgs().contains(ARM2_PLUGIN);
        // Only inject our own oracle when no external override is already present
        // (e.g. when RobustnessSweep has already installed an error oracle).
        // We track whether WE injected so that we only clear what we set.
        boolean arm2InjectedHere = false;
        if (arm2Active && !LlmReflectionModel.hasOracle()) {
            injectArm2Oracle(config);
            arm2InjectedHere = true;
        } else if (arm2Active) {
            logger.info("[ConfigRunner] A2 external oracle already set; skipping own injection");
        }

        try {
            String[] args = buildMainArgs(config, benchmarkCp, mainClass);
            logger.info("[ConfigRunner] {} / {} / {}", config.id(), benchmarkCp, mainClass);

            long t0 = System.nanoTime();
            Main.main(args);
            long timeMs = (System.nanoTime() - t0) / 1_000_000L;

            PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
            MetricCollector.Metrics m = collector.collect(result);

            logger.info("[ConfigRunner] {} done in {}ms: avgPts={}", config.id(), timeMs, m.avgPtsSize());
            return m;
        } finally {
            if (arm2InjectedHere) {
                LlmReflectionModel.clearOracle();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Arg construction
    // -----------------------------------------------------------------------

    /**
     * Builds the argument array for {@code Main.main}.
     *
     * <p>Format:
     * {@code -cp <benchmarkCp> -m <mainClass> -a pta=<mergedPtaArgs>}
     */
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

    /**
     * Merges {@code configPtaArgs} with {@link #BASE_OPTS}.
     *
     * <p>{@code plugins:[...]} tokens are extracted and accumulated so that any
     * plugins in the config are unified into a single {@code plugins:[...]}
     * directive appended at the end (matching the pattern in
     * {@code pascal.taie.analysis.Tests.getPTAArgs}).
     *
     * @param configPtaArgs semicolon-separated PTA option string from the config
     * @return merged semicolon-separated PTA arg string
     */
    private static String mergePtaArgs(String configPtaArgs) {
        List<String> result = new ArrayList<>(BASE_OPTS);
        List<String> plugins = new ArrayList<>();

        for (String token : configPtaArgs.split(";")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("plugins:[")) {
                // Extract individual class names from "plugins:[a,b,c]"
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
    // Arm ② oracle injection
    // -----------------------------------------------------------------------

    /**
     * Loads a {@link pta.llm.MockOracle} from the {@code llm-mock-file} option
     * embedded in {@code config.ptaArgs()} and injects it into
     * {@link LlmReflectionModel} via the static setter.
     *
     * <p>If no {@code llm-mock-file} is present the oracle is set to a no-op
     * instance (empty answer map, empty-string default), which causes
     * {@code LlmReflectionModel} to make no LLM queries.
     */
    private static void injectArm2Oracle(Configs.Config config) {
        String mockFile = extractOption(config.ptaArgs(), "llm-mock-file");
        pta.llm.LlmOracle oracle;
        if (mockFile != null && !mockFile.isBlank()) {
            oracle = ArmOracleFactory.loadMockOracle(Path.of(mockFile));
            logger.info("[ConfigRunner] A2 oracle loaded from {}", mockFile);
        } else {
            // No mock file: use a no-op oracle so the plugin stays offline-safe.
            oracle = new pta.llm.MockOracle(java.util.Map.of(), "");
            logger.info("[ConfigRunner] A2 using no-op oracle (no llm-mock-file)");
        }
        LlmReflectionModel.setOracle(oracle);
    }

    /**
     * Extracts the value of a {@code key:value} option from a
     * semicolon-separated PTA args string.
     *
     * @return the value string, or {@code null} if the key is absent
     */
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
