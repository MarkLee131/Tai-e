package pta.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link ApiKeyResolver#parseLine} — the forgiving file parser. */
public class ApiKeyResolverTest {

    @Test
    void bareKeyLine() {
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("AIzaABC"));
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("  AIzaABC  "));
    }

    @Test
    void dotenvStyle() {
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("GEMINI_API_KEY=AIzaABC"));
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("GOOGLE_API_KEY=AIzaABC"));
    }

    @Test
    void exportAndQuotesStripped() {
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("export GEMINI_API_KEY='AIzaABC'"));
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("export GEMINI_API_KEY=\"AIzaABC\""));
        assertEquals("AIzaABC", ApiKeyResolver.parseLine("GEMINI_API_KEY=\"AIzaABC\""));
    }

    @Test
    void commentsAndBlanksYieldNothing() {
        assertEquals("", ApiKeyResolver.parseLine(""));
        assertEquals("", ApiKeyResolver.parseLine("   "));
        assertEquals("", ApiKeyResolver.parseLine("# GEMINI_API_KEY=AIzaABC"));
    }

    @Test
    void unrelatedAssignmentIgnored() {
        // A KEY=VALUE line whose name we don't recognize must not be taken as a key.
        assertEquals("", ApiKeyResolver.parseLine("SOME_OTHER=whatever"));
    }
}
