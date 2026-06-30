package pta.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the Gemini API key for the LLM-augmented arms from a project-local
 * {@code .api_key_env} file (preferred) or environment variables (fallback).
 *
 * <h2>Resolution order</h2>
 * The first non-blank key found wins:
 * <ol>
 *   <li>file at system property {@code -Dllm.api-key-file=<path>} (explicit override)</li>
 *   <li>{@code .api_key_env} in the working directory</li>
 *   <li>{@code ../.api_key_env} (one level up — the repo root above {@code Tai-e/})</li>
 *   <li>environment variable {@code GEMINI_API_KEY}</li>
 *   <li>environment variable {@code GOOGLE_API_KEY}</li>
 * </ol>
 * The file takes precedence over the environment on purpose: it lets the project
 * "call the key file" directly and avoids a stale shell-exported value shadowing it.
 *
 * <h2>File format</h2>
 * Line-oriented; blank lines and {@code #} comments are ignored. A key line is any
 * of (an optional leading {@code export } and surrounding quotes are stripped):
 * <pre>
 *   AIza... / AQ....              # bare key (classic or newer "AQ." format)
 *   GEMINI_API_KEY=AIza...        # dotenv style (also accepts GOOGLE_API_KEY)
 *   export GEMINI_API_KEY="AQ..." # shell style (file stays `source`-able)
 * </pre>
 * The same file is therefore usable both by this resolver and by {@code source .api_key_env}.
 */
public final class ApiKeyResolver {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyResolver.class);

    private static final String SYS_PROP = "llm.api-key-file";
    private static final String DEFAULT_FILE = ".api_key_env";
    private static final String PARENT_FILE = "../.api_key_env";
    private static final List<String> ENV_VARS = List.of("GEMINI_API_KEY", "GOOGLE_API_KEY");

    private ApiKeyResolver() {
    }

    /**
     * Resolves the API key, or returns {@code ""} if none is configured.
     * Logs the source (never the key) and warns if the key does not look like a
     * Google AI Studio Gemini key (which start with {@code AIza}).
     */
    public static String resolve() {
        // 1. explicit file via system property
        String prop = System.getProperty(SYS_PROP);
        if (prop != null && !prop.isBlank()) {
            String k = readKeyFile(Path.of(prop));
            if (!k.isEmpty()) {
                return accept(k, SYS_PROP + "=" + prop);
            }
        }
        // 2/3. project-local files (cwd, then parent)
        for (String candidate : List.of(DEFAULT_FILE, PARENT_FILE)) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                String k = readKeyFile(p);
                if (!k.isEmpty()) {
                    return accept(k, p.toAbsolutePath().normalize().toString());
                }
            }
        }
        // 4/5. environment variables
        for (String env : ENV_VARS) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                return accept(v.strip(), "env:" + env);
            }
        }
        logger.warn("No Gemini API key found (checked {}, {}, {}, env {}). "
                + "Live LLM calls will fail; put a key in {} as 'GEMINI_API_KEY=AIza...'.",
                SYS_PROP, DEFAULT_FILE, PARENT_FILE, ENV_VARS, DEFAULT_FILE);
        return "";
    }

    private static String accept(String key, String source) {
        // Two valid formats authenticate via the ?key= URL param / x-goog-api-key
        // header: classic AI Studio keys ("AIza…") and Google's newer
        // security-hardened keys ("AQ.…"). Anything else is likely wrong.
        if (!key.startsWith("AIza") && !key.startsWith("AQ.")) {
            logger.warn("Gemini API key from {} starts with neither 'AIza' nor 'AQ.' — "
                    + "it may not be a valid generativelanguage API key.", source);
        }
        logger.info("Gemini API key loaded from {} (…{}, {} chars)",
                source, mask(key), key.length());
        return key;
    }

    /** Returns the last 4 characters for a non-revealing log hint. */
    private static String mask(String key) {
        return key.length() <= 4 ? "****" : key.substring(key.length() - 4);
    }

    /**
     * Reads the first key-bearing line from {@code file}. Returns {@code ""} on
     * any error or if no key line is present.
     */
    private static String readKeyFile(Path file) {
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String key = parseLine(raw);
                if (!key.isEmpty()) {
                    return key;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read API key file {}: {}", file, e.getMessage());
        }
        return "";
    }

    /** Parses one line into a key, or {@code ""} if the line carries no key. */
    static String parseLine(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return "";
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).strip();
        }
        int eq = line.indexOf('=');
        if (eq >= 0) {
            String name = line.substring(0, eq).strip();
            String value = stripQuotes(line.substring(eq + 1).strip());
            // Only honor recognized names; ignore unrelated KEY=VALUE lines.
            return ENV_VARS.contains(name) ? value : "";
        }
        // No '=': treat the whole (de-quoted) line as a bare key.
        return stripQuotes(line);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                 || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
