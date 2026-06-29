package pta.arm3;

import pascal.taie.config.AnalysisOptions;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Builds the {@link LlmOracle} for Arm 3 from analysis options. Self-contained
 * to arm3 to avoid cross-arm coupling.
 *
 * <p>When the {@code llm-mock-file} option is set, the file's entire content is
 * treated as the canned LLM response and returned for every query (an offline
 * {@link pta.llm.MockOracle}-style oracle). This keeps all Arm 3 tests fully
 * offline. When the option is unset, no live oracle is configured here (the
 * live Gemini key is disabled in this environment), and {@code null} is
 * returned so the plugin becomes a no-op.
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

    private ArmOracleFactory() {
    }

    public static LlmOracle fromOptions(AnalysisOptions options) {
        if (oracleOverride != null) {
            return oracleOverride;
        }
        String mockFile = options.has("llm-mock-file")
                ? options.getString("llm-mock-file") : null;
        if (mockFile == null) {
            return null;
        }
        final String canned;
        try {
            canned = Files.readString(Path.of(mockFile));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read llm-mock-file: " + mockFile, e);
        }
        // A constant oracle returning the canned response for any query.
        return q -> new LlmResponse(canned, true, 0.0);
    }

    /** Visible for symmetry/testing: a constant oracle over a literal answer. */
    public static LlmOracle constant(String answer) {
        Map<String, String> ignored = Map.of();
        return q -> new LlmResponse(ignored.getOrDefault(q.contextId(), answer),
                true, 0.0);
    }
}
