package pta.arm3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.config.AnalysisOptions;
import pta.llm.ApiKeyResolver;
import pta.llm.CostMeter;
import pta.llm.GeminiOracle;
import pta.llm.LlmOracle;
import pta.llm.PromptCache;

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
 * Arm 3 tests fully offline. When the option is unset, a live {@link GeminiOracle}
 * is built using the key resolved by {@link ApiKeyResolver} (file/env); if no key
 * is available, {@code null} is returned so arm ③ proposes nothing (a sound no-op).
 *
 * <p>A static override (set via {@link #setOracle}/{@link #clearOracle}) takes
 * precedence over all options. This is used by {@link pta.eval.RobustnessSweep}
 * to inject an {@link pta.llm.ErrorInjectingOracle} for each sweep point.
 * When no override is set the behaviour is unchanged.
 */
public final class ArmOracleFactory {

    private static final Logger logger = LoggerFactory.getLogger(ArmOracleFactory.class);

    /** Gemini Flash-Lite pricing (USD per token): $0.10/1M input, $0.40/1M output. */
    private static final double IN_RATE = 1e-7;

    private static final double OUT_RATE = 4e-7;

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
        if (mockFile != null && !mockFile.isBlank()) {
            // Offline per-key mock: lines "<answer> <method-name>" keyed by the
            // candidate method's simple name; "default <answer>" sets the fallback.
            return pta.arm1.ArmOracleFactory.loadMockOracle(Path.of(mockFile));
        }
        // No mock file: build a live GeminiOracle if a key is available.
        String apiKey = ApiKeyResolver.resolve();
        if (apiKey.isEmpty()) {
            logger.info("Arm③ has no mock file and no API key — proposing nothing (sound no-op)");
            return null;
        }
        String model = optString(options, "llm-model", "gemini-2.5-flash-lite");
        double budget = parseDouble(optString(options, "llm-budget", "100.0"), 100.0);
        String cacheDir = optString(options, "llm-cache-dir", ".llm-cache");
        logger.info("Arm③ using live GeminiOracle (model={}, budget=${}, cache={})",
                model, budget, cacheDir);
        return new GeminiOracle(model, apiKey,
                new PromptCache(Path.of(cacheDir)),
                new CostMeter(budget, IN_RATE, OUT_RATE));
    }

    private static String optString(AnalysisOptions options, String key, String def) {
        if (!options.has(key)) {
            return def;
        }
        Object v = options.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
