package pta.llm;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class GeminiOracle implements LlmOracle {
    /** Max attempts for transient failures (HTTP 429/5xx, network blips). */
    private static final int MAX_ATTEMPTS = 6;
    /** Base backoff (ms); doubles each retry, capped at 30s. */
    private static final long BASE_BACKOFF_MS = 1000;

    private final String model, apiKey;
    private final PromptCache cache; private final CostMeter meter;
    private final HttpClient http = HttpClient.newHttpClient();
    public GeminiOracle(String model, String apiKey, PromptCache cache, CostMeter meter) {
        this.model = model; this.apiKey = apiKey; this.cache = cache; this.meter = meter;
    }
    @Override public LlmResponse ask(LlmQuery q) {
        String genConfig = genConfig(model);
        // Key the cache on model + generation config so changing the config (e.g. the
        // token cap) invalidates stale — possibly truncated — entries.
        String cacheKey = cacheKeyFor(model);
        Optional<String> hit = cache.get(cacheKey, q.prompt());
        if (hit.isPresent()) return new LlmResponse(hit.get(), true, 0.0);
        // estimate before calling; refuse if it would break the cap
        double est = meter.estimate(q.prompt(), q.prompt()); // assume output ~ prompt size for pre-check
        if (meter.wouldExceed(est))
            throw new CostMeter.BudgetExceededException("budget would be exceeded by live call for " + q.contextId());
        String body = "{\"contents\":[{\"parts\":[{\"text\":" + jsonString(q.prompt())
            + "}]}],\"generationConfig\":{" + genConfig + "}}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    String text = extractText(resp.body());
                    double real = meter.estimate(q.prompt(), text);
                    meter.charge(real);
                    if (text.isBlank()) {
                        // HTTP-200 with no text part (safety block / empty candidate /
                        // MAX_TOKENS-with-thought-only): retryable, and must NOT be
                        // cached — a poisoned blank entry would replay forever.
                        last = new RuntimeException("Gemini empty response for "
                                + q.contextId());
                    } else {
                        cache.put(cacheKey, q.prompt(), text);
                        return new LlmResponse(text, false, real);
                    }
                } else if (code != 429 && code < 500) {
                    // 4xx other than 429 → fail fast.
                    throw new RuntimeException("Gemini HTTP " + code + ": " + resp.body());
                } else {
                    // 429 (rate limit) and 5xx (server) are transient → retry.
                    last = new RuntimeException("Gemini HTTP " + code + " for " + q.contextId());
                }
            } catch (java.io.IOException e) {
                last = new RuntimeException("Gemini I/O failure for " + q.contextId(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Gemini call interrupted for " + q.contextId(), e);
            }
            if (attempt < MAX_ATTEMPTS) backoff(attempt);
        }
        throw new RuntimeException("Gemini call failed after " + MAX_ATTEMPTS
            + " attempts for " + q.contextId(), last);
    }

    /**
     * Generation config: cap output tokens generously enough for LIST answers (arm②
     * asks for one fully-qualified class name per line — 64 tokens truncated
     * multi-candidate lists mid-name, silently losing correct proposals), and for
     * Gemini 2.5 models disable "thinking" (which otherwise spends ~1000+ reasoning
     * tokens → ~7s latency + cost).
     */
    private static String genConfig(String model) {
        String genConfig = "\"temperature\":0,\"maxOutputTokens\":256";
        if (model.contains("2.5")) {
            genConfig += ",\"thinkingConfig\":{\"thinkingBudget\":0}";
        }
        return genConfig;
    }

    /** The cache key: model + generation config (config changes invalidate entries). */
    static String cacheKeyFor(String model) {
        // -Darm2.cacheSalt forces cache misses without touching stored entries — used to
        // measure true cold-cache cost/latency in experiments.
        String salt = System.getProperty("arm2.cacheSalt", "");
        return model + "|" + genConfig(model) + (salt.isEmpty() ? "" : "|salt=" + salt);
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
    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // JSON forbids raw control characters; prompts embed raw IR text
                    // (string literals from analyzed bytecode can contain any of them).
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append("\"").toString();
    }
    // pull the first candidate's text out of the Gemini JSON response
    private static String extractText(String json) {
        String marker = "\"text\":";
        int i = json.indexOf(marker);
        if (i < 0) return "";
        i = json.indexOf('"', i + marker.length());
        if (i < 0) return "";
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
