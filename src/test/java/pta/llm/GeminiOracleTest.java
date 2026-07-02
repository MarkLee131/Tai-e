package pta.llm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GeminiOracleTest {
    @Test
    void servesFromCacheWithoutNetwork(@TempDir Path dir) {
        PromptCache cache = new PromptCache(dir);
        // seed under the oracle's versioned cache key (model + generation config, so
        // config changes invalidate stale — possibly truncated — entries)
        cache.put(GeminiOracle.cacheKeyFor("gemini-2.0-flash"), "PROMPT", "cached-answer");
        CostMeter meter = new CostMeter(100.0, 1e-7, 3e-7);
        GeminiOracle o = new GeminiOracle("gemini-2.0-flash", "FAKE_KEY", cache, meter);
        LlmResponse r = o.ask(new LlmQuery("k", "PROMPT", "Lx;"));
        assertTrue(r.fromCache());
        assertEquals("cached-answer", r.raw());
        assertEquals(0.0, meter.spent(), 1e-12); // cache hit costs nothing
    }
    @Test
    void refusesWhenBudgetWouldExceedOnMiss(@TempDir Path dir) {
        CostMeter meter = new CostMeter(0.0, 1.0, 1.0); // zero budget
        GeminiOracle o = new GeminiOracle("gemini-2.0-flash", "FAKE_KEY", new PromptCache(dir), meter);
        assertThrows(CostMeter.BudgetExceededException.class,
            () -> o.ask(new LlmQuery("k", "MISS", "Ly;")));
    }

    // Opt-in live smoke test: ONE real gemini-2.0-flash call. Skipped unless
    // LLM_LIVE is set, so CI/offline runs never touch the network.
    @Test
    void liveSmokeTest(@TempDir Path dir) {
        if (System.getenv("LLM_LIVE") == null) {
            return; // inert by default
        }
        String key = System.getenv("GEMINI_API_KEY");
        assertNotNull(key, "GEMINI_API_KEY must be set for the live smoke test");
        PromptCache cache = new PromptCache(dir);
        CostMeter meter = new CostMeter(1.0, 1e-7, 3e-7);
        GeminiOracle o = new GeminiOracle("gemini-2.0-flash", key, cache, meter);
        long t0 = System.nanoTime();
        LlmResponse r = o.ask(new LlmQuery("smoke", "Reply with the single word OK", "Lsmoke;"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(r.fromCache());
        assertFalse(r.raw().isBlank());
        assertTrue(meter.spent() > 0.0 && meter.spent() < 0.01);
        assertTrue(cache.get(GeminiOracle.cacheKeyFor("gemini-2.0-flash"),
                "Reply with the single word OK").isPresent());
        System.out.println("[live] model=gemini-2.0-flash latency=" + ms + "ms"
            + " spent=$" + meter.spent() + " reply=" + r.raw().trim());
    }
}
