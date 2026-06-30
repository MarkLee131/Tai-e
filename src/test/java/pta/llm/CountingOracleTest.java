package pta.llm;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link CountingOracle}.
 */
class CountingOracleTest {

    /** A MockOracle that returns a response with known cost. */
    private static LlmOracle costOracle(double costPerCall) {
        return q -> new LlmResponse("answer", false, costPerCall);
    }

    @Test
    void initialCountAndCostAreZero() {
        CountingOracle co = new CountingOracle(costOracle(0.5));
        assertEquals(0, co.queryCount(), "queryCount must be 0 before any ask");
        assertEquals(0.0, co.totalCostUsd(), 1e-12, "totalCostUsd must be 0.0 before any ask");
    }

    @Test
    void countIncrementsOnEachAsk() {
        CountingOracle co = new CountingOracle(costOracle(0.0));
        co.ask(new LlmQuery("k", "p", "id1"));
        co.ask(new LlmQuery("k", "p", "id2"));
        co.ask(new LlmQuery("k", "p", "id3"));
        assertEquals(3, co.queryCount(), "queryCount must equal number of ask() calls");
    }

    @Test
    void costAccumulates() {
        CountingOracle co = new CountingOracle(costOracle(0.25));
        co.ask(new LlmQuery("k", "p", "id1"));
        co.ask(new LlmQuery("k", "p", "id2"));
        assertEquals(0.50, co.totalCostUsd(), 1e-12, "totalCostUsd must sum estCostUsd from each response");
    }

    @Test
    void cacheHitCostIsZero() {
        // Cache hits return estCostUsd = 0.0 (from MockOracle)
        MockOracle mock = new MockOracle(Map.of("id1", "cached"), "default");
        CountingOracle co = new CountingOracle(mock);
        LlmResponse resp = co.ask(new LlmQuery("k", "p", "id1"));
        assertEquals(1, co.queryCount(), "cache hit still counts as a query");
        assertEquals(0.0, co.totalCostUsd(), 1e-12, "cache hit has 0 cost");
        assertEquals("cached", resp.raw(), "delegate response is forwarded");
    }

    @Test
    void delegateResponseIsForwarded() {
        LlmOracle delegate = q -> new LlmResponse("hello-" + q.contextId(), false, 1.0);
        CountingOracle co = new CountingOracle(delegate);
        LlmResponse r = co.ask(new LlmQuery("k", "p", "ctx99"));
        assertEquals("hello-ctx99", r.raw(), "raw response text must be forwarded from delegate");
        assertEquals(1.0, r.estCostUsd(), 1e-12, "estCostUsd must be forwarded from delegate");
    }
}
