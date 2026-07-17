package pta.llm;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Oracle for OpenAI-compatible chat-completions APIs (OpenAI, DeepSeek — their
 * request/response wire formats are identical). Mirrors {@link GeminiOracle}
 * exactly in structure and discipline: versioned prompt cache, cost-meter
 * pre-check, bounded retry with exponential backoff, blank responses retryable
 * and never cached.
 */
public class OpenAiCompatOracle implements LlmOracle {
    /** Max attempts for transient failures (HTTP 429/5xx, network blips). */
    private static final int MAX_ATTEMPTS = 6;
    /** Base backoff (ms); doubles each retry, capped at 30s. */
    private static final long BASE_BACKOFF_MS = 1000;

    private final String baseUrl, model, apiKey;
    private final PromptCache cache; private final CostMeter meter;
    private final HttpClient http = HttpClient.newHttpClient();
    public OpenAiCompatOracle(String baseUrl, String model, String apiKey,
                              PromptCache cache, CostMeter meter) {
        // tolerate a trailing '/' so both ".../v1" and ".../v1/" produce the same
        // endpoint AND the same cache key
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model; this.apiKey = apiKey; this.cache = cache; this.meter = meter;
    }
    @Override public LlmResponse ask(LlmQuery q) {
        // Key the cache on baseUrl + model + generation config so changing the config
        // (e.g. the token cap) — or the provider — invalidates stale entries.
        String cacheKey = cacheKeyFor(baseUrl, model);
        Optional<String> hit = cache.get(cacheKey, q.prompt());
        if (hit.isPresent()) return new LlmResponse(hit.get(), true, 0.0);
        // estimate before calling; refuse if it would break the cap
        double est = meter.estimate(q.prompt(), q.prompt()); // assume output ~ prompt size for pre-check
        if (meter.wouldExceed(est))
            throw new CostMeter.BudgetExceededException("budget would be exceeded by live call for " + q.contextId());
        String body = "{\"model\":" + GeminiOracle.jsonString(model)
            + ",\"messages\":[{\"role\":\"user\",\"content\":" + GeminiOracle.jsonString(q.prompt())
            + "}]," + GEN_CONFIG + "}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    String text = extractContent(resp.body());
                    double real = meter.estimate(q.prompt(), text);
                    meter.charge(real);
                    if (text.isBlank()) {
                        // HTTP-200 with no assistant text (content filter / empty
                        // choice / length-capped-to-nothing): retryable, and must NOT
                        // be cached — a poisoned blank entry would replay forever.
                        last = new RuntimeException("OpenAI-compat empty response for "
                                + q.contextId());
                    } else {
                        cache.put(cacheKey, q.prompt(), text);
                        return new LlmResponse(text, false, real);
                    }
                } else if (code != 429 && code != 404 && code < 500) {
                    // 4xx other than 429/404 → fail fast.
                    throw new RuntimeException("OpenAI-compat HTTP " + code + ": " + resp.body());
                } else {
                    // 429 (rate limit), 5xx (server), and 404 are transient → retry.
                    // 404: observed 2026-07-10 as an INTERMITTENT server-side response on
                    // Gemini generateContent (20 sporadic 404s in one 11-benchmark pass,
                    // same model/URL succeeding before and after) — treating it as
                    // permanent silently collapsed three benchmarks to the no-oracle
                    // floor. Mirrored here: the same failure mode would be just as
                    // silent on this channel.
                    last = new RuntimeException("OpenAI-compat HTTP " + code + " for " + q.contextId());
                }
            } catch (java.io.IOException e) {
                last = new RuntimeException("OpenAI-compat I/O failure for " + q.contextId(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("OpenAI-compat call interrupted for " + q.contextId(), e);
            }
            if (attempt < MAX_ATTEMPTS) backoff(attempt);
        }
        throw new RuntimeException("OpenAI-compat call failed after " + MAX_ATTEMPTS
            + " attempts for " + q.contextId(), last);
    }

    /**
     * Generation config: temperature 0 for determinism; cap output tokens generously
     * enough for LIST answers (arm② asks for one fully-qualified class name per line —
     * a small cap truncated multi-candidate lists mid-name, silently losing correct
     * proposals). Same 256-token cap as {@link GeminiOracle}; no model-specific
     * tweaks are needed on this channel.
     */
    private static final String GEN_CONFIG = "\"temperature\":0,\"max_tokens\":256";

    /** The cache key: baseUrl + model + generation config (config changes invalidate entries). */
    static String cacheKeyFor(String baseUrl, String model) {
        // -Darm2.cacheSalt forces cache misses without touching stored entries — used to
        // measure true cold-cache cost/latency in experiments.
        String salt = System.getProperty("arm2.cacheSalt", "");
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl + "|" + model + "|" + GEN_CONFIG + (salt.isEmpty() ? "" : "|salt=" + salt);
    }

    /** Sleeps with exponential backoff before the next retry. */
    private static void backoff(int attempt) {
        long ms = Math.min(BASE_BACKOFF_MS << (attempt - 1), 30_000L);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted during backoff", e);
        }
    }

    // pull choices[0].message.content out of the chat-completions JSON response
    // (the first "content" key in the body belongs to the first choice's message)
    private static String extractContent(String json) {
        String marker = "\"content\":";
        int i = json.indexOf(marker);
        if (i < 0) return "";
        i += marker.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        // "content" can be JSON null (e.g. tool-call replies) — then the next quote in
        // the body belongs to some LATER field, and scanning to it would return field
        // names as the answer. Treat non-string content as empty (retryable, uncached).
        if (i >= json.length() || json.charAt(i) != '"') return "";
        StringBuilder b = new StringBuilder();
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\') {
                char n = json.charAt(++j);
                switch (n) { case 'n' -> b.append('\n'); case 't' -> b.append('\t');
                    case 'r' -> b.append('\r'); case '"' -> b.append('"');
                    case '\\' -> b.append('\\'); default -> b.append(n); }
            } else if (c == '"') break;
            else b.append(c);
        }
        return b.toString();
    }
}
