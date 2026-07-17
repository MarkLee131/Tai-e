package pta.llm;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
public record LlmResponse(String raw, boolean fromCache, double estCostUsd) {
    public List<String> asLines() {
        // Drop markdown code-fence lines (```, ```json, ...). Some vendors
        // (OpenAI/DeepSeek) wrap list answers in a fenced block; a fence is
        // never a valid Java class/member name, so stripping it here keeps
        // cross-vendor parsing uniform. Gemini never emits fences, so this is
        // a no-op for the primary oracle.
        return Arrays.stream(raw.split("\n"))
            .map(String::trim).filter(s -> !s.isEmpty())
            .filter(s -> !s.startsWith("```"))
            .collect(Collectors.toList());
    }
}
