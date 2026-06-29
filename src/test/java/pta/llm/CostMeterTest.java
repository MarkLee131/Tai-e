package pta.llm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CostMeterTest {
    @Test
    void accumulatesAndCaps() {
        CostMeter m = new CostMeter(0.10, 0.0, 0.0);
        m.charge(0.04); m.charge(0.04);
        assertEquals(0.08, m.spent(), 1e-9);
        assertTrue(m.wouldExceed(0.05));
        assertThrows(CostMeter.BudgetExceededException.class, () -> m.charge(0.05));
    }
    @Test
    void estimateUsesTokenRates() {
        // 8 input chars -> 2 tok, 4 output chars -> 1 tok
        CostMeter m = new CostMeter(1.0, 1.0, 2.0);
        assertEquals(2 * 1.0 + 1 * 2.0, m.estimate("12345678", "abcd"), 1e-9);
    }
}
