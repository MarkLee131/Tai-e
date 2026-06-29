package pta.llm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Optional;

public class PromptCache {
    private final Path dir;
    public PromptCache(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static String key(String model, String prompt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((model + " " + prompt).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private Path file(String model, String prompt) { return dir.resolve(key(model, prompt) + ".json"); }
    public Optional<String> get(String model, String prompt) {
        Path f = file(model, prompt);
        if (!Files.exists(f)) return Optional.empty();
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            return Optional.of(decode(extract(json, "response")));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public void put(String model, String prompt, String response) {
        String json = "{\"model\":\"" + encode(model) + "\",\"prompt\":\"" + encode(prompt)
            + "\",\"response\":\"" + encode(response) + "\"}";
        try { Files.writeString(file(model, prompt), json, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    // minimal JSON string escaping/unescaping (no external dep needed for this shape)
    private static String encode(String s) {
        StringBuilder b = new StringBuilder();
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
        return b.toString();
    }
    private static String decode(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    default -> b.append(n);
                }
            } else b.append(c);
        }
        return b.toString();
    }
    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) return "";
        i += marker.length();
        StringBuilder b = new StringBuilder();
        for (int j = i; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\') { b.append(c).append(json.charAt(++j)); continue; }
            if (c == '"') break;
            b.append(c);
        }
        return b.toString();
    }
}
