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

package pta.arm1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.config.AnalysisOptions;
import pta.llm.CostMeter;
import pta.llm.GeminiOracle;
import pta.llm.LlmOracle;
import pta.llm.MockOracle;
import pta.llm.PromptCache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the {@link LlmOracle} used by the LLM-guided arms from analysis
 * options. If option {@code llm-mock-file} is set, an offline
 * {@link MockOracle} is loaded from that file (for tests / reproducible runs);
 * otherwise a live {@link GeminiOracle} is constructed (model, budget cap and
 * cache directory taken from options).
 *
 * <p>A static override (set via {@link #setOracle}/{@link #clearOracle}) takes
 * precedence over all options. This is used by {@link pta.eval.RobustnessSweep}
 * to inject an {@link pta.llm.ErrorInjectingOracle} for each sweep point without
 * touching production paths. When no override is set the behaviour is unchanged.
 */
public final class ArmOracleFactory {

    private static final Logger logger = LoggerFactory.getLogger(ArmOracleFactory.class);

    /** Gemini Flash-Lite pricing (USD per token): $0.10/1M input, $0.40/1M output. */
    private static final double IN_RATE = 1e-7;  // $0.10 / 1_000_000

    private static final double OUT_RATE = 4e-7; // $0.40 / 1_000_000

    /**
     * Optional static override. When non-null, {@link #fromOptions} returns this
     * oracle directly, ignoring all option values. Intended for
     * {@link pta.eval.RobustnessSweep} sweep injection; production code never
     * sets this field (it remains null unless explicitly overridden).
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
            logger.info("Arm① using static oracle override (RobustnessSweep injection)");
            return oracleOverride;
        }
        String mockFile = optString(options, "llm-mock-file", null);
        if (mockFile != null && !mockFile.isBlank()) {
            logger.info("Arm① using offline MockOracle from {}", mockFile);
            return loadMockOracle(Path.of(mockFile));
        }
        String model = optString(options, "llm-model", "gemini-2.5-flash-lite");
        double budget = parseDouble(optString(options, "llm-budget", "100.0"), 100.0);
        String cacheDir = optString(options, "llm-cache-dir", ".llm-cache");
        String apiKey = firstNonNull(
                System.getenv("GEMINI_API_KEY"), System.getenv("GOOGLE_API_KEY"), "");
        logger.info("Arm① using live GeminiOracle (model={}, budget=${}, cache={})",
                model, budget, cacheDir);
        return new GeminiOracle(model, apiKey,
                new PromptCache(Path.of(cacheDir)),
                new CostMeter(budget, IN_RATE, OUT_RATE));
    }

    /**
     * Loads a {@link MockOracle} from a simple line-oriented file.
     * <p>Each non-empty, non-{@code #} line is {@code "<answer> <key>"}: the
     * answer is the first whitespace-delimited token (e.g. {@code YES}/{@code NO})
     * and the remainder of the line (which may contain spaces) is the exact
     * query key (a method signature). A line {@code "default <answer>"} sets the
     * fallback answer for keys not listed.
     */
    public static MockOracle loadMockOracle(Path file) {
        Map<String, String> answers = new HashMap<>();
        String def = "NO";
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    continue; // malformed line: ignore
                }
                String answer = line.substring(0, sp).strip();
                String rest = line.substring(sp + 1).strip();
                if (answer.equals("default")) {
                    def = rest;
                } else {
                    answers.put(rest, answer);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load LLM mock file: " + file, e);
        }
        return new MockOracle(answers, def);
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

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
