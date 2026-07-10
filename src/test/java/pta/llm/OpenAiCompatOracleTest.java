package pta.llm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatOracleTest {
    private static final String OPENAI_BASE = "https://api.openai.com/v1";
    private static final String DEEPSEEK_BASE = "https://api.deepseek.com";

    @Test
    void servesFromCacheWithoutNetwork(@TempDir Path dir) {
        PromptCache cache = new PromptCache(dir);
        // seed under the oracle's versioned cache key (baseUrl + model + generation
        // config, so config changes invalidate stale — possibly truncated — entries)
        cache.put(OpenAiCompatOracle.cacheKeyFor(OPENAI_BASE, "gpt-4o-mini"),
                "PROMPT", "cached-answer");
        CostMeter meter = new CostMeter(100.0, 1.5e-7, 6e-7);
        OpenAiCompatOracle o = new OpenAiCompatOracle(OPENAI_BASE, "gpt-4o-mini",
                "FAKE_KEY", cache, meter);
        LlmResponse r = o.ask(new LlmQuery("k", "PROMPT", "Lx;"));
        assertTrue(r.fromCache());
        assertEquals("cached-answer", r.raw());
        assertEquals(0.0, meter.spent(), 1e-12); // cache hit costs nothing
    }

    @Test
    void cacheKeyDistinguishesProvidersAndModels(@TempDir Path dir) {
        // the same prompt cached for one provider/model must NOT be replayed for
        // another — the key is versioned by (baseUrl, model, generation config)
        PromptCache cache = new PromptCache(dir);
        cache.put(OpenAiCompatOracle.cacheKeyFor(OPENAI_BASE, "gpt-4o-mini"),
                "PROMPT", "openai-answer");
        CostMeter meter = new CostMeter(0.0, 1.0, 1.0); // zero budget: a miss must refuse
        OpenAiCompatOracle deepseek = new OpenAiCompatOracle(DEEPSEEK_BASE,
                "deepseek-chat", "FAKE_KEY", cache, meter);
        assertThrows(CostMeter.BudgetExceededException.class,
                () -> deepseek.ask(new LlmQuery("k", "PROMPT", "Lx;")));
    }

    @Test
    void refusesWhenBudgetWouldExceedOnMiss(@TempDir Path dir) {
        CostMeter meter = new CostMeter(0.0, 1.0, 1.0); // zero budget
        OpenAiCompatOracle o = new OpenAiCompatOracle(OPENAI_BASE, "gpt-4o-mini",
                "FAKE_KEY", new PromptCache(dir), meter);
        assertThrows(CostMeter.BudgetExceededException.class,
                () -> o.ask(new LlmQuery("k", "MISS", "Ly;")));
    }

    // Opt-in live smoke test: ONE real chat/completions call against whichever
    // provider has a key set (OpenAI preferred, DeepSeek fallback). Skipped unless
    // LLM_LIVE is set, so CI/offline runs never touch the network.
    @Test
    void liveSmokeTest(@TempDir Path dir) {
        if (System.getenv("LLM_LIVE") == null) {
            return; // inert by default
        }
        String baseUrl, model, key;
        String openaiKey = System.getenv("OPENAI_API_KEY");
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            baseUrl = OPENAI_BASE; model = "gpt-4o-mini"; key = openaiKey;
        } else {
            assertNotNull(deepseekKey,
                    "OPENAI_API_KEY or DEEPSEEK_API_KEY must be set for the live smoke test");
            baseUrl = DEEPSEEK_BASE; model = "deepseek-chat"; key = deepseekKey;
        }
        PromptCache cache = new PromptCache(dir);
        CostMeter meter = new CostMeter(1.0, 1.5e-7, 6e-7);
        OpenAiCompatOracle o = new OpenAiCompatOracle(baseUrl, model, key, cache, meter);
        long t0 = System.nanoTime();
        LlmResponse r = o.ask(new LlmQuery("smoke", "Reply with the single word OK", "Lsmoke;"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(r.fromCache());
        assertFalse(r.raw().isBlank());
        assertTrue(meter.spent() > 0.0 && meter.spent() < 0.01);
        assertTrue(cache.get(OpenAiCompatOracle.cacheKeyFor(baseUrl, model),
                "Reply with the single word OK").isPresent());
        System.out.println("[live] model=" + model + " latency=" + ms + "ms"
                + " spent=$" + meter.spent() + " reply=" + r.raw().trim());
    }
}
