package pta.llm;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class CorruptingOracleTest {

    private static final LlmOracle TRUE_ORACLE =
            q -> new LlmResponse("com.example.True\ncom.example.Second", false, 0.0);
    private static final LlmQuery Q =
            new LlmQuery("llm-class", "p", "<Foo: void bar()>/3");

    private static CorruptingOracle mk(String mode, double rate) {
        return new CorruptingOracle(TRUE_ORACLE, mode, rate, 1L, null, null,
                () -> List.of("app.A", "app.B", "app.C"));
    }

    @Test void rateZeroIsPassthrough() {
        assertEquals("com.example.True\ncom.example.Second", mk("silent", 0.0).ask(Q).raw());
    }

    @Test void silentEmptiesTheAnswer() {
        assertEquals("", mk("silent", 1.0).ask(Q).raw());
    }

    @Test void truncateKeepsFirstLineOnly() {
        assertEquals("com.example.True", mk("truncate", 1.0).ask(Q).raw());
    }

    @Test void fixedReplacesWithGivenName() {
        assertEquals("evil.Clazz", mk("fixed:evil.Clazz", 1.0).ask(Q).raw());
    }

    @Test void loadableDrawsFromPool() {
        assertTrue(List.of("app.A", "app.B", "app.C").contains(mk("loadable", 1.0).ask(Q).raw()));
    }

    @Test void randomEmitsUnloadableGarbage() {
        assertTrue(mk("random", 1.0).ask(Q).raw().matches("adv\\.gen\\.C\\d+"));
    }

    @Test void deterministicAcrossCalls() {
        CorruptingOracle a = mk("loadable", 0.5), b = mk("loadable", 0.5);
        assertEquals(a.ask(Q).raw(), b.ask(Q).raw());
    }

    @Test void rateIsPerQueryDeterministic() {
        // over 200 distinct contextIds at rate 0.5, corrupted fraction lands in (0.3, 0.7)
        int corrupted = 0;
        CorruptingOracle o = mk("silent", 0.5);
        for (int i = 0; i < 200; i++) {
            LlmQuery q = new LlmQuery("llm-class", "p", "<Site" + i + ": void m()>/0");
            if (o.ask(q).raw().isEmpty()) corrupted++;
        }
        assertTrue(corrupted > 60 && corrupted < 140, "got " + corrupted);
    }

    @Test void onlySiteFilterLimitsCorruption() {
        CorruptingOracle o = new CorruptingOracle(TRUE_ORACLE, "silent", 1.0, 1L,
                "<Foo:", null, List::of);
        assertEquals("", o.ask(Q).raw());
        LlmQuery other = new LlmQuery("llm-class", "p", "<Bar: void baz()>/1");
        assertEquals("com.example.True\ncom.example.Second", o.ask(other).raw());
    }

    @Test void exceptSiteFilterProtectsMatching() {
        CorruptingOracle o = new CorruptingOracle(TRUE_ORACLE, "silent", 1.0, 1L,
                null, "<Foo:", List::of);
        assertEquals("com.example.True\ncom.example.Second", o.ask(Q).raw());
        LlmQuery other = new LlmQuery("llm-class", "p", "<Bar: void baz()>/1");
        assertEquals("", o.ask(other).raw());
    }

    @Test void wrapIfConfiguredIsNoopWithoutProperty() {
        System.clearProperty("arm2.corrupt");
        assertSame(TRUE_ORACLE, CorruptingOracle.wrapIfConfigured(TRUE_ORACLE, List::of));
        assertNull(CorruptingOracle.wrapIfConfigured(null, List::of));
    }

    @Test void wrapIfConfiguredWrapsWhenPropertySet() {
        try {
            System.setProperty("arm2.corrupt", "silent");
            LlmOracle o = CorruptingOracle.wrapIfConfigured(TRUE_ORACLE, List::of);
            assertEquals("", o.ask(Q).raw());
        } finally {
            System.clearProperty("arm2.corrupt");
        }
    }
}
