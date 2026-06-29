package pta.llm;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MockOracleTest {
    @Test
    void mockReturnsMappedAnswer() {
        MockOracle m = new MockOracle(Map.of("Lfoo;", "yes"), "no");
        assertEquals("yes", m.ask(new LlmQuery("k", "p", "Lfoo;")).raw());
        assertEquals("no", m.ask(new LlmQuery("k", "p", "Lbar;")).raw());
    }
    @Test
    void injectionIsDeterministicAndScalesWithP() {
        MockOracle base = new MockOracle(Map.of(), "good");
        ErrorInjectingOracle none = new ErrorInjectingOracle(base, 0.0, r -> "BAD", 42);
        ErrorInjectingOracle all = new ErrorInjectingOracle(base, 1.0, r -> "BAD", 42);
        assertEquals("good", none.ask(new LlmQuery("k", "p", "Lx;")).raw());
        assertEquals("BAD", all.ask(new LlmQuery("k", "p", "Lx;")).raw());
        // determinism: same instance, same query -> same result
        ErrorInjectingOracle half = new ErrorInjectingOracle(base, 0.5, r -> "BAD", 42);
        String a = half.ask(new LlmQuery("k", "p", "Lx;")).raw();
        String b = half.ask(new LlmQuery("k", "p", "Lx;")).raw();
        assertEquals(a, b);
    }
}
