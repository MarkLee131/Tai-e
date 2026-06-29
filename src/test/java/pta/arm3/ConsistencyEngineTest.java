package pta.arm3;

import org.junit.jupiter.api.Test;
import pta.arm3.datalog.ConsistencyEngine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConsistencyEngineTest {

    private static boolean containsPair(List<AliasFact> facts, String a, String b) {
        return facts.stream().anyMatch(f ->
                (f.groupA().equals(a) && f.groupB().equals(b))
                        || (f.groupA().equals(b) && f.groupB().equals(a)));
    }

    /**
     * base says alias(A,B)=true (sound). LLM proposes never-alias(A,B) and
     * never-alias(C,D). The (A,B) proposal contradicts the base and must be
     * rejected; (C,D) is unrelated and must survive.
     */
    @Test
    void rejectsContradictoryNeverAlias() {
        List<AliasFact> base = List.of(new AliasFact("A", "B", true));
        List<AliasFact> proposed = List.of(
                new AliasFact("A", "B", false),
                new AliasFact("C", "D", false));

        ConsistencyEngine engine = new ConsistencyEngine();
        List<AliasFact> survivors = engine.accept(base, proposed);

        assertFalse(containsPair(survivors, "A", "B"),
                "never-alias(A,B) contradicts base alias(A,B), must be rejected");
        assertTrue(containsPair(survivors, "C", "D"),
                "never-alias(C,D) is unrelated to base, must survive");
        assertEquals(1, survivors.size());

        List<AliasFact> rejected = engine.getRejected();
        assertEquals(1, rejected.size());
        assertTrue(containsPair(rejected, "A", "B"));
    }

    /**
     * Transitive closure: base alias(A,B) and alias(B,C) put A,B,C in one
     * alias class, so never-alias(A,C) is contradicted transitively.
     */
    @Test
    void rejectsTransitivelyContradictoryFact() {
        List<AliasFact> base = List.of(
                new AliasFact("A", "B", true),
                new AliasFact("B", "C", true));
        List<AliasFact> proposed = List.of(new AliasFact("A", "C", false));

        ConsistencyEngine engine = new ConsistencyEngine();
        List<AliasFact> survivors = engine.accept(base, proposed);

        assertTrue(survivors.isEmpty());
        assertTrue(containsPair(engine.getRejected(), "A", "C"));
    }
}
