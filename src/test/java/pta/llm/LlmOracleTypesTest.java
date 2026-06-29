package pta.llm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class LlmOracleTypesTest {
    @Test
    void responseSplitsLines() {
        LlmResponse r = new LlmResponse("a\n\n  b \nc", true, 0.0);
        assertEquals(List.of("a", "b", "c"), r.asLines());
        assertTrue(r.fromCache());
    }
    @Test
    void queryHoldsFields() {
        LlmQuery q = new LlmQuery("cs-critical", "Is foo critical?", "Lfoo;");
        assertEquals("cs-critical", q.kind());
        assertEquals("Lfoo;", q.contextId());
    }
}
