package pta.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link ConfigRunner} and {@link Configs}.
 *
 * <p>Uses {@code BoxAlias} from {@code src/test/resources/pta/eval} as the
 * canonical object-sensitivity benchmark.  {@code Box} exposes instance
 * methods {@code set(Object)} and {@code get()}, so the receiver's heap
 * allocation site is the context discriminator under 2-obj.
 *
 * <ul>
 *   <li>Under context-insensitive analysis (B0), {@code Box.get()} is
 *       analysed once; its return variable points to both {@code A-alloc}
 *       and {@code B-alloc}, so {@code x} and {@code y} each have
 *       points-to size 2.</li>
 *   <li>Under 2-object-sensitive analysis (B1), each call site of
 *       {@code get()} has a distinct heap context (the receiver's alloc
 *       site), so {@code x} -> {A-alloc} and {@code y} -> {B-alloc}:
 *       points-to size 1 each.  This strictly reduces {@code avgPtsSize}
 *       relative to B0.</li>
 * </ul>
 */
public class ConfigRunnerTest {

    private static final String BENCHMARK_CP = "src/test/resources/pta/eval";

    private static final String MAIN_CLASS = "BoxAlias";

    /**
     * Runs B0 (CI) and B1 (2-obj) on {@code BoxAlias} and asserts that B1
     * achieves strictly smaller {@code avgPtsSize} than B0.
     *
     * <p>This test is non-vacuous: under CI, {@code Box.get()} merges both
     * allocation sites so each result variable sees two objects; under 2-obj
     * the two {@code Box} allocation sites separate the contexts so each
     * result variable sees exactly one object.  If B1 were broken (e.g.
     * context sensitivity disabled), the strict inequality would fail.
     */
    @Test
    void b1IsStrictlyMorePreciseThanB0OnBoxAlias() {
        ConfigRunner runner = new ConfigRunner();

        MetricCollector.Metrics b0 = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        MetricCollector.Metrics b1 = runner.run(Configs.B1, BENCHMARK_CP, MAIN_CLASS);

        assertNotNull(b0, "B0 (CI) must complete without exception");
        assertNotNull(b1, "B1 (2-obj) must complete without exception");

        assertTrue(b0.avgPtsSize() > 0,
                "B0 must see non-empty points-to sets");

        assertTrue(b1.avgPtsSize() < b0.avgPtsSize(),
                "B1 (2-obj) avgPtsSize=" + b1.avgPtsSize()
                + " must be STRICTLY LESS than B0 (CI) avgPtsSize=" + b0.avgPtsSize()
                + " — BoxAlias instance-method context separation must hold");
    }
}
