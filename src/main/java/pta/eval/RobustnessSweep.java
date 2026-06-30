package pta.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pta.arm2.LlmReflectionModel;
import pta.llm.ErrorInjectingOracle;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;
import pta.llm.MockOracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Error-injection robustness sweep for LLM-augmented pointer analysis.
 *
 * <p>For each error rate in {@code errorRates}, wraps the arm's oracle in an
 * {@link ErrorInjectingOracle} and runs the pointer analysis, recording both
 * precision metrics ({@link MetricCollector.Metrics}) and recall versus the
 * p=0 (error-free) ground-truth call-graph edge set.
 *
 * <h2>Oracle injection mechanism</h2>
 * <p>Each arm exposes a static setter/clearer for its oracle (injected before
 * {@code Main.main} is called and cleared in a {@code finally} block):
 * <ul>
 *   <li><b>Arm ①</b> ({@code advanced:llm}): {@link pta.arm1.ArmOracleFactory#setOracle} /
 *       {@link pta.arm1.ArmOracleFactory#clearOracle}</li>
 *   <li><b>Arm ②</b> ({@code pta.arm2.LlmReflectionModel}): {@link LlmReflectionModel#setOracle} /
 *       {@link LlmReflectionModel#clearOracle}</li>
 *   <li><b>Arm ③</b> ({@code advanced:llm-cafd}, sound heap cloning):
 *       {@link pta.arm3.ArmOracleFactory#setOracle} /
 *       {@link pta.arm3.ArmOracleFactory#clearOracle}</li>
 *   <li><b>Unsound CAFD-style</b> ({@code pta.eval.UnsoundCafdStylePlugin}):
 *       {@link UnsoundCafdStylePlugin#setOracle} / {@link UnsoundCafdStylePlugin#clearOracle}</li>
 * </ul>
 * The static override in each factory takes precedence over any option-file
 * oracle, so existing production paths are unaffected when no override is set.
 *
 * <h2>Soundness vs unsoundness contrast</h2>
 * <p>Arm ① is sound-by-construction: wrong CS-selection decisions degrade
 * precision but never remove a real call edge, because context-insensitive
 * analysis (the fallback for deselected methods) is always a sound
 * over-approximation of 2-obj. Recall stays 1.0 at every error rate.
 *
 * <p>{@link UnsoundCafdStylePlugin} represents the CAFD-style design where the
 * LLM answer is applied without a consistency check. A corrupt oracle returns a
 * self-contradictory fact ({@code never-alias ARunner ARunner}) that the plugin
 * applies directly, filtering ARunner objects from a wrapper result variable and
 * dropping a real call edge. Recall falls below 1.0.
 *
 * <h2>Ground truth</h2>
 * <p>Ground truth = call-graph edge set of the p=0 (error-free) run of the same
 * arm. The first element of {@code errorRates} must be 0.0; otherwise the sweep
 * fails with a clear error. Recall for the p=0 point is trivially 1.0 by
 * definition.
 */
public class RobustnessSweep {

    private static final Logger logger = LoggerFactory.getLogger(RobustnessSweep.class);

    /** Default error rates swept by {@link #run}. */
    public static final double[] DEFAULT_ERROR_RATES = {0.0, 0.25, 0.5, 0.75, 1.0};

    /** Deterministic seed for {@link ErrorInjectingOracle} across all sweep runs. */
    static final long SEED = 42L;

    // Arm identification tags (substrings of ptaArgs).
    // NOTE: ARM3_TAG ("advanced:llm-cafd") contains ARM1_TAG ("advanced:llm") as a
    // substring, so ARM3 MUST be tested BEFORE ARM1 in injectOracle/clearOracle.
    private static final String ARM1_TAG   = "advanced:llm";
    private static final String ARM2_TAG   = "pta.arm2.LlmReflectionModel";
    private static final String ARM3_TAG   = "advanced:llm-cafd";
    private static final String UNSOUND_TAG = "pta.eval.UnsoundCafdStylePlugin";

    private final ConfigRunner runner   = new ConfigRunner();
    private final SoundnessChecker checker = new SoundnessChecker();

    // -----------------------------------------------------------------------
    // Public result record
    // -----------------------------------------------------------------------

    /**
     * Immutable result for a single error-rate sweep point.
     *
     * @param errorRate the LLM error rate in [0.0, 1.0]
     * @param m         precision/cost metrics for this run
     * @param recall    fraction of ground-truth call-graph edges retained
     */
    public record SweepPoint(double errorRate, MetricCollector.Metrics m, double recall) {}

    // -----------------------------------------------------------------------
    // Config factories
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Configs.Config} for the intentionally-unsound CAFD-style
     * contrast plugin (NOT one of our proposed arms).
     */
    public static Configs.Config unsoundCafdConfig() {
        return new Configs.Config("CAFD-unsound",
                "cs:ci;plugins:[pta.eval.UnsoundCafdStylePlugin]");
    }

    // -----------------------------------------------------------------------
    // Primary sweep entry point
    // -----------------------------------------------------------------------

    /**
     * Runs the robustness sweep using the default corrupt function derived from
     * the arm type. See {@link #run(Configs.Config, double[], String, String, LlmOracle, Function)}
     * for the full-control overload that the test suite uses.
     *
     * <p>Default corrupt functions:
     * <ul>
     *   <li>Arm ①: return {@code "NO"} (all methods deselected → fall back to CI)</li>
     *   <li>Other arms: return {@code ""} (no-op; may not show contrast for unsound arm)</li>
     * </ul>
     *
     * @param arm         pointer-analysis configuration
     * @param errorRates  error rates to sweep; {@code errorRates[0]} must be 0.0
     * @param benchmarkCp classpath directory of the benchmark
     * @param main        main class name
     * @return sweep results in the same order as {@code errorRates}
     */
    public List<SweepPoint> run(Configs.Config arm, double[] errorRates,
                                String benchmarkCp, String main) {
        LlmOracle base = buildBaseOracle(arm);
        Function<LlmResponse, String> corrupt = buildDefaultCorrupt(arm);
        return run(arm, errorRates, benchmarkCp, main, base, corrupt);
    }

    /**
     * Full-control sweep: caller supplies the base oracle and corrupt function.
     *
     * <p>The sweep sets the arm's static oracle override before each run and
     * clears it in a {@code finally} block. The first rate in {@code errorRates}
     * should be 0.0 to establish the ground truth; if not, the ground truth is
     * collected from the first rate provided.
     *
     * @param arm         pointer-analysis configuration
     * @param errorRates  error rates to sweep (must contain at least one element)
     * @param benchmarkCp classpath directory of the benchmark
     * @param main        main class name
     * @param baseOracle  the error-free oracle (used at errorRate=0.0)
     * @param corrupt     transforms a correct oracle response into a corrupted one
     * @return one {@link SweepPoint} per entry in {@code errorRates}
     */
    public List<SweepPoint> run(Configs.Config arm, double[] errorRates,
                                String benchmarkCp, String main,
                                LlmOracle baseOracle,
                                Function<LlmResponse, String> corrupt) {
        double[] rates = (errorRates != null && errorRates.length > 0)
                ? errorRates : DEFAULT_ERROR_RATES;

        List<SweepPoint> points = new ArrayList<>();
        Set<SoundnessChecker.EdgeKey> groundTruthKeys = null;

        for (double p : rates) {
            LlmOracle oracle = (p == 0.0)
                    ? baseOracle
                    : new ErrorInjectingOracle(baseOracle, p, corrupt, SEED);

            MetricCollector.Metrics m;
            try {
                // injectOracle is inside the try so clearOracle in finally always runs.
                injectOracle(arm, oracle);
                m = runner.run(arm, benchmarkCp, main);
            } finally {
                clearOracle(arm);
            }

            // Collect call-graph edge keys from the completed World.
            PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
            Set<SoundnessChecker.EdgeKey> keys = checker.toKeySet(result);

            if (groundTruthKeys == null) {
                // p=0 run provides the ground truth for this arm.
                groundTruthKeys = keys;
                logger.info("[sweep] {} p=0 ground-truth EdgeKey set size: {} edges "
                        + "(avgPtsSize={})",
                        arm.id(), groundTruthKeys.size(), m.avgPtsSize());
            } else {
                logger.info("[sweep] {} p={} EdgeKey set size: {} edges (avgPtsSize={})",
                        arm.id(), p, keys.size(), m.avgPtsSize());
            }

            double recall = checker.recallByKeys(groundTruthKeys, keys);
            logger.info("[sweep] {} p={} recall={} reachable={}",
                    arm.id(), p, recall, m.reachableMethods());
            points.add(new SweepPoint(p, m, recall));
        }
        return points;
    }

    // -----------------------------------------------------------------------
    // Per-arm oracle injection
    // -----------------------------------------------------------------------

    private static void injectOracle(Configs.Config arm, LlmOracle oracle) {
        String args = arm.ptaArgs();
        // ARM3 ("advanced:llm-cafd") must be checked BEFORE ARM1 ("advanced:llm").
        if (args.contains(ARM3_TAG)) {
            pta.arm3.ArmOracleFactory.setOracle(oracle);
        } else if (args.contains(ARM1_TAG)) {
            pta.arm1.ArmOracleFactory.setOracle(oracle);
        } else if (args.contains(ARM2_TAG)) {
            LlmReflectionModel.setOracle(oracle);
        } else if (args.contains(UNSOUND_TAG)) {
            UnsoundCafdStylePlugin.setOracle(oracle);
        } else {
            logger.warn("[sweep] no known oracle injection point for config '{}'; "
                    + "running with no oracle override", arm.id());
        }
    }

    private static void clearOracle(Configs.Config arm) {
        String args = arm.ptaArgs();
        // ARM3 ("advanced:llm-cafd") must be checked BEFORE ARM1 ("advanced:llm").
        if (args.contains(ARM3_TAG)) {
            pta.arm3.ArmOracleFactory.clearOracle();
        } else if (args.contains(ARM1_TAG)) {
            pta.arm1.ArmOracleFactory.clearOracle();
        } else if (args.contains(ARM2_TAG)) {
            LlmReflectionModel.clearOracle();
        } else if (args.contains(UNSOUND_TAG)) {
            UnsoundCafdStylePlugin.clearOracle();
        }
    }

    // -----------------------------------------------------------------------
    // Base oracle and default corrupt function
    // -----------------------------------------------------------------------

    /**
     * Loads the base oracle from the {@code llm-mock-file} option embedded in
     * the config's ptaArgs. If no mock file is specified, returns a no-op oracle
     * that returns {@code ""} for every query.
     */
    private static LlmOracle buildBaseOracle(Configs.Config arm) {
        String mockPath = extractOption(arm.ptaArgs(), "llm-mock-file");
        if (mockPath != null && !mockPath.isBlank()) {
            return pta.arm1.ArmOracleFactory.loadMockOracle(
                    java.nio.file.Path.of(mockPath));
        }
        return new MockOracle(Map.of(), "");
    }

    /**
     * Returns a default corrupt function appropriate for the arm type.
     *
     * <ul>
     *   <li>Arm ①: flip YES → NO (forces deselection → CI fallback → recall=1.0)</li>
     *   <li>Others: return {@code ""} (no-op; the test should supply an explicit
     *       corrupt function via the overloaded {@code run} method)</li>
     * </ul>
     */
    private static Function<LlmResponse, String> buildDefaultCorrupt(Configs.Config arm) {
        if (arm.ptaArgs().contains(ARM1_TAG)) {
            return r -> {
                List<String> lines = r.asLines();
                return (!lines.isEmpty() && lines.get(0).equalsIgnoreCase("YES"))
                        ? "NO" : "YES";
            };
        }
        return r -> "";
    }

    /** Extracts the value of {@code key:value} from a semicolon-separated ptaArgs string. */
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
