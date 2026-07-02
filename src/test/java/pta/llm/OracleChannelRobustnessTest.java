package pta.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2/L3: the LLM channel must not poison the persistent cache with blank responses
 * (safety-block / empty-candidate replies would otherwise short-circuit every future
 * run at zero cost), and prompts embedding raw IR text must be JSON-safe for ALL
 * control characters, not just the common four.
 */
public class OracleChannelRobustnessTest {

    @TempDir
    Path tmp;

    @Test
    void blankResponsesAreNotCached() {
        PromptCache cache = new PromptCache(tmp);
        cache.put("m", "p", "");
        assertTrue(cache.get("m", "p").isEmpty(),
                "L2: a blank response must not be cached (poison-proof)");
        cache.put("m", "p", "  \n");
        assertTrue(cache.get("m", "p").isEmpty(),
                "L2: whitespace-only responses must not be cached either");
        cache.put("m", "p", "real answer");
        assertTrue(cache.get("m", "p").isPresent(),
                "sanity: real answers are cached");
    }

    @Test
    void controlCharactersAreJsonEscaped() {
        String out = GeminiOracle.jsonString("a\u0001b\fc");
        assertFalse(out.chars().anyMatch(c -> c < 0x20),
                "L3: no raw control characters may survive into the JSON body: " + out);
        assertTrue(out.contains("\\u0001") && out.contains("\\u000c"),
                "L3: control chars must be \\uXXXX-escaped: " + out);
    }
}
