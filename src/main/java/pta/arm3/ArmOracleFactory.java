package pta.arm3;

import pascal.taie.config.AnalysisOptions;
import pta.llm.LlmOracle;

import java.nio.file.Path;

/**
 * Builds the {@link LlmOracle} for Arm 3 from analysis options. Self-contained
 * to arm3 to avoid cross-arm coupling.
 *
 * <p>When the {@code llm-mock-file} option is set, an offline
 * {@link pta.llm.MockOracle} is loaded from that file: each line
 * {@code "<answer> <method-name>"} maps a candidate method's simple name to a
 * YES/NO judgment, and {@code "default <answer>"} sets the fallback (see
 * {@link pta.arm1.ArmOracleFactory#loadMockOracle}). {@link LlmWrapperProposer}
 * queries this oracle per candidate method (keyed by simple name) to decide
 * which methods the LLM proposes as fresh-allocation wrappers. This keeps all
 * Arm 3 tests fully offline. When the option is unset, no live oracle is
 * configured here (the live Gemini key is disabled in this environment), and
 * {@code null} is returned so arm ③ proposes nothing (a sound no-op).
 *
 * <p>A static override (set via {@link #setOracle}/{@link #clearOracle}) takes
 * precedence over all options. This is used by {@link pta.eval.RobustnessSweep}
 * to inject an {@link pta.llm.ErrorInjectingOracle} for each sweep point.
 * When no override is set the behaviour is unchanged.
 */
public final class ArmOracleFactory {

    /**
     * Optional static override. When non-null, {@link #fromOptions} returns this
     * oracle directly, ignoring all option values. Intended for
     * {@link pta.eval.RobustnessSweep} sweep injection; production code never
     * sets this field.
     */
    private static volatile LlmOracle oracleOverride;

    /**
     * Injects an oracle override that takes precedence over options.
     * Must be paired with {@link #clearOracle()} in a {@code finally} block.
     */
    public static void setOracle(LlmOracle oracle) {
        oracleOverride = oracle;
    }

    /** Clears the static oracle override set by {@link #setOracle}. */
    public static void clearOracle() {
        oracleOverride = null;
    }

    /** Returns {@code true} when a static oracle override is currently installed. */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }

    private ArmOracleFactory() {
    }

    public static LlmOracle fromOptions(AnalysisOptions options) {
        if (oracleOverride != null) {
            return oracleOverride;
        }
        String mockFile = options.has("llm-mock-file")
                ? options.getString("llm-mock-file") : null;
        if (mockFile == null || mockFile.isBlank()) {
            return null;
        }
        // Offline per-key mock: lines "<answer> <method-name>" keyed by the
        // candidate method's simple name; "default <answer>" sets the fallback.
        return pta.arm1.ArmOracleFactory.loadMockOracle(Path.of(mockFile));
    }
}
