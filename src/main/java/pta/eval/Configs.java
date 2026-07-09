package pta.eval;

import java.util.List;

/**
 * Static registry of pointer-analysis configurations used by the evaluation
 * pipeline.
 *
 * <p>Each {@link Config} records a short identifier and the semicolon-separated
 * PTA option string that {@link ConfigRunner} merges with the fixed base options
 * ({@code implicit-entries:false;only-app:true;distinguish-string-constants:all})
 * before calling {@code pascal.taie.Main}.
 *
 * <h2>Baselines (no external oracle required)</h2>
 * <ul>
 *   <li>{@link #B0} — context-insensitive ({@code cs:ci})</li>
 *   <li>{@link #B1} — 2-object-sensitive ({@code cs:2-obj})</li>
 *   <li>{@link #B2z} — 2-obj + Zipper pre-filter</li>
 *   <li>{@link #B2s} — 2-obj + Scaler pre-filter</li>
 *   <li>{@link #B3} — CAFD allocator-context abstraction</li>
 * </ul>
 *
 * <p>{@link #ALL} is the list of all five baselines; it intentionally omits
 * arms because arms require a benchmark-specific LLM mock file whose path is
 * only known at runtime. Use the factory methods ({@link #a1}, {@link #a2},
 * {@link #a3}) to create arm configs for a specific experiment.
 *
 * <h2>Arms (require a mock / live oracle)</h2>
 * <ul>
 *   <li>{@link #a1(String)} — LLM-guided selective context sensitivity (arm ①)</li>
 *   <li>{@link #a2(String)} — LLM reflection target resolution (arm ②).
 *       Note: arm ② uses a <em>static</em> oracle (
 *       {@link pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel#setOracle}) injected by
 *       {@link ConfigRunner} before each run; the {@code llm-mock-file} value
 *       in the config's {@code ptaArgs} is used by the runner to load the
 *       oracle, but is NOT read by {@code LlmReflectionModel} itself.</li>
 *   <li>{@link #a3(String)} — neuro-symbolic sound heap cloning (arm ③)</li>
 * </ul>
 */
public final class Configs {

    // -----------------------------------------------------------------------
    // Config record
    // -----------------------------------------------------------------------

    /**
     * Immutable pair of a short config identifier and the semicolon-separated
     * PTA option string passed to {@link ConfigRunner}.
     *
     * @param id      short label used in CSV output (e.g. {@code "B0"}, {@code "A1"})
     * @param ptaArgs semicolon-separated PTA options that are merged with base
     *                options by {@link ConfigRunner} (e.g. {@code "cs:ci"})
     */
    public record Config(String id, String ptaArgs) {}

    // -----------------------------------------------------------------------
    // Baseline constants
    // -----------------------------------------------------------------------

    /** B0: context-insensitive. Lowest precision, fastest runtime. */
    public static final Config B0 = new Config("B0", "cs:ci");

    /** B1: 2-object-sensitive. Full context sensitivity up to depth 2. */
    public static final Config B1 = new Config("B1", "cs:2-obj");

    /** B2z: 2-object-sensitive with Zipper pre-analysis to select CS methods. */
    public static final Config B2z = new Config("B2z", "cs:2-obj;advanced:zipper");

    /** B2s: 2-object-sensitive with Scaler pre-analysis to select CS methods. */
    public static final Config B2s = new Config("B2s", "cs:2-obj;advanced:scaler");

    /** B3: CAFD allocator-context abstraction (context-adaptive fly-data). */
    public static final Config B3 = new Config("B3", "advanced:cafd");

    // -----------------------------------------------------------------------
    // ALL: ordered list of all baselines
    // -----------------------------------------------------------------------

    /**
     * All five baseline configurations in evaluation order (B0 → B3).
     *
     * <p>Arm configs are omitted because they require a benchmark-specific mock
     * file; create them via {@link #a1}, {@link #a2}, {@link #a3}.
     */
    public static final List<Config> ALL = List.of(B0, B1, B2z, B2s, B3);

    // -----------------------------------------------------------------------
    // Arm factory methods
    // -----------------------------------------------------------------------

    /**
     * Arm ①: LLM-guided selective context sensitivity.
     *
     * <p>Activated by {@code advanced:llm} + {@code llm-mock-file:<mockFile>},
     * read by {@link pta.arm1.ArmOracleFactory#fromOptions}.
     *
     * @param mockFile path to the line-oriented mock-oracle file
     * @return arm ① config
     */
    public static Config a1(String mockFile) {
        return new Config("A1",
                "cs:2-obj;advanced:llm;llm-mock-file:" + mockFile);
    }

    /**
     * Arm ②: LLM reflection target resolution.
     *
     * <p>The plugin ({@code pta.arm2.LlmReflectionModel}) is registered via
     * {@code plugins:[...]}, but it does <em>not</em> read {@code llm-mock-file}
     * from options. Instead, {@link ConfigRunner} detects this config, loads a
     * {@link pta.llm.MockOracle} from {@code mockFile}, and injects it via
     * {@link pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel#setOracle} before the run (and calls
     * {@link pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel#clearOracle} afterward).
     *
     * @param mockFile path to the mock-oracle file (answers are class names)
     * @return arm ② config
     */
    public static Config a2(String mockFile) {
        // Arm ② is the production reflection model, activated by
        // reflection-inference:llm; ConfigRunner injects the oracle via
        // LlmInferenceModel.setOracle (loaded from mockFile).
        return new Config("A2",
                "reflection-inference:llm;llm-mock-file:" + mockFile);
    }

    /**
     * Arm ③: Neuro-symbolic SOUND per-callsite heap cloning ("CAFD done right").
     *
     * <p>Activated by {@code advanced:llm-cafd} + {@code llm-mock-file:<mockFile>}.
     * The LLM proposes fresh-allocation wrapper candidates (read via
     * {@link pta.arm3.ArmOracleFactory#fromOptions}); B3's
     * {@link pta.baseline.cafd.WrapperDetector} confirms them; only the confirmed
     * subset is cloned per call site (see {@link pta.arm3.LlmWrapperProposer}).
     *
     * @param mockFile path to the per-method proposal file (lines of
     *                 {@code YES <method>} / {@code NO <method>}; {@code default <ans>})
     * @return arm ③ config
     */
    public static Config a3(String mockFile) {
        return new Config("A3",
                "advanced:llm-cafd;llm-mock-file:" + mockFile);
    }

    private Configs() {
    }
}
