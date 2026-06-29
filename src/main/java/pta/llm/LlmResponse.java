package pta.llm;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
public record LlmResponse(String raw, boolean fromCache, double estCostUsd) {
    public List<String> asLines() {
        return Arrays.stream(raw.split("\n"))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
}
