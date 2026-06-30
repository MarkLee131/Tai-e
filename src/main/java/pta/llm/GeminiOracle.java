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
        Optional<String> hit = cache.get(model, q.prompt());
        if (hit.isPresent()) return new LlmResponse(hit.get(), true, 0.0);
        // estimate before calling; refuse if it would break the cap
        double est = meter.estimate(q.prompt(), q.prompt()); // assume output ~ prompt size for pre-check
        if (meter.wouldExceed(est))
            throw new CostMeter.BudgetExceededException("budget would be exceeded by live call for " + q.contextId());
        // The arms only need a short answer (YES/NO on the first line, or a class
        // name). Cap output tokens, and for Gemini 2.5 models disable "thinking"
        // (which otherwise spends ~1000+ reasoning tokens → ~7s latency + cost) —
        // a binary precision-criticality judgment does not need it.
        String genConfig = "\"temperature\":0,\"maxOutputTokens\":64";
        if (model.contains("2.5")) {
            genConfig += ",\"thinkingConfig\":{\"thinkingBudget\":0}";
        }
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
                    cache.put(model, q.prompt(), text);
                    return new LlmResponse(text, false, real);
                }
                // 429 (rate limit) and 5xx (server) are transient → retry; 4xx → fail fast.
                if (code != 429 && code < 500)
                    throw new RuntimeException("Gemini HTTP " + code + ": " + resp.body());
                last = new RuntimeException("Gemini HTTP " + code + " for " + q.contextId());
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
    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
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
