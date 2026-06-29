package pta.llm;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class GeminiOracle implements LlmOracle {
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
        String body = "{\"contents\":[{\"parts\":[{\"text\":" + jsonString(q.prompt())
            + "}]}],\"generationConfig\":{\"temperature\":0}}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
                throw new RuntimeException("Gemini HTTP " + resp.statusCode() + ": " + resp.body());
            String text = extractText(resp.body());
            double real = meter.estimate(q.prompt(), text);
            meter.charge(real);
            cache.put(model, q.prompt(), text);
            return new LlmResponse(text, false, real);
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("Gemini call failed for " + q.contextId(), e);
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
