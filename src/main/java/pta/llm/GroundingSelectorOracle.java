package pta.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic no-LLM baseline oracle (the "LLM-necessity" ablation a hostile
 * reviewer demanded). It IGNORES any language model and, for each query, returns
 * exactly the machine-derived grounding candidates that {@code LlmInferenceModel.buildPrompt}
 * already embedded in the prompt:
 *
 * <ol>
 *   <li>the classpath id-match list — {@code "Classes on the classpath matching the
 *       application id (choose the exact one): [...]"};</li>
 *   <li>the downcast cone — {@code "... Concrete subtypes on the classpath: [...]"}
 *       (only present for abstract/interface downcast targets);</li>
 *   <li>the config-value grounding — {@code "Config values found on the classpath for
 *       these keys (high-confidence candidates): [...]"} (config/resource-driven names).</li>
 * </ol>
 *
 * It proposes ALL of these candidates and lets the disposer's Φ realizability check and
 * type fence filter/clamp them exactly as it would for a real LLM answer. If the prompt
 * carries no grounding candidates for a site (no id-match, no cone, no config value), it
 * ABSTAINS (returns empty) — the honest behavior: with no LLM there is no name to invent
 * from thin air.
 *
 * <p>The response is a pure function of the prompt, so the analysis stays deterministic.
 * No live query is ever issued (cost = 0).
 */
public class GroundingSelectorOracle implements LlmOracle {

    private static final Logger logger =
            LoggerFactory.getLogger(GroundingSelectorOracle.class);

    /** Exact grounding-slot markers emitted by {@code LlmInferenceModel.buildPrompt}. */
    private static final String[] MARKERS = {
            "Classes on the classpath matching the application id (choose the exact one): ",
            "Concrete subtypes on the classpath: ",
            "Config values found on the classpath for these keys (high-confidence candidates): ",
    };

    /**
     * Convention/entry-point class names named verbatim in the out-of-band context slot
     * ({@code arm2.appContext}) — the DaCapo {@code dacapo.<id>.<Id>Harness} wrapper and a
     * real-world app's declared main class. Harvested only in {@code -full} mode: this is
     * NOT a classpath-candidate slot, it is the RQ2 context string, so a candidate-list
     * selector must not use it. The {@code -full} variant answers the sharper question —
     * once the deterministic template name is also on the table, does any LLM advantage
     * remain?
     */
    private static final Pattern HARNESS =
            Pattern.compile("dacapo\\.[a-z0-9]+\\.[A-Z][A-Za-z0-9_$]*Harness");
    private static final Pattern MAIN_CLASS =
            Pattern.compile("main class ([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)");

    /** {@code true} for {@code arm2.oracle=grounding-selector-full} (also harvest context). */
    private final boolean includeContext;

    public GroundingSelectorOracle() {
        this(false);
    }

    public GroundingSelectorOracle(boolean includeContext) {
        this.includeContext = includeContext;
    }

    @Override
    public LlmResponse ask(LlmQuery q) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String marker : MARKERS) {
            candidates.addAll(parseListAfter(q.prompt(), marker));
        }
        if (includeContext) {
            Matcher mh = HARNESS.matcher(q.prompt());
            while (mh.find()) {
                candidates.add(mh.group());
            }
            Matcher mm = MAIN_CLASS.matcher(q.prompt());
            while (mm.find()) {
                candidates.add(mm.group(1));
            }
        }
        List<String> out = new ArrayList<>(candidates);
        logger.info("[grounding-selector] {} at {} -> {} candidate(s): {}",
                q.kind(), q.contextId(), out.size(), out);
        // fromCache=true / cost=0: no live model call was ever made.
        return new LlmResponse(String.join("\n", out), true, 0.0);
    }

    /**
     * Every {@code [a, b, c]} list that immediately follows an occurrence of {@code marker}
     * in {@code prompt}. The prompt renders {@code List.toString()}, so elements are joined
     * by {@code ", "} and never contain {@code '['}, {@code ']'} or {@code ','} (fully
     * qualified class/member names and property values), making the split unambiguous.
     * Multiple occurrences of the same marker are all harvested.
     */
    private static List<String> parseListAfter(String prompt, String marker) {
        List<String> result = new ArrayList<>();
        int from = 0;
        while (true) {
            int m = prompt.indexOf(marker, from);
            if (m < 0) {
                break;
            }
            int open = prompt.indexOf('[', m + marker.length());
            int nl = prompt.indexOf('\n', m + marker.length());
            // The list must sit on the marker's own line, right after it.
            if (open < 0 || (nl >= 0 && open > nl)) {
                from = m + marker.length();
                continue;
            }
            int close = prompt.indexOf(']', open);
            if (close < 0) {
                break;
            }
            String inner = prompt.substring(open + 1, close).trim();
            if (!inner.isEmpty()) {
                for (String tok : inner.split(",")) {
                    String name = tok.trim();
                    if (!name.isEmpty()) {
                        result.add(name);
                    }
                }
            }
            from = close + 1;
        }
        return result;
    }
}
