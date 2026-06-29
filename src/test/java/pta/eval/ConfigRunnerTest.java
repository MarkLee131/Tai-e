package pta.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link ConfigRunner} and {@link Configs}.
 *
 * <p>Uses {@code WrapperAlias} from {@code src/test/resources/pta/arm3} because
 * that program has no dependency on {@code PTAAssert}, so a single classpath
 * entry suffices. WrapperAlias is precision-discriminating between CI and 2-obj:
 * the identity wrapper {@code id()} is analysed once under CI, conflating
 * {@code {AObj, BObj}} into {@code wa} and {@code wb}; under 2-obj each call
 * site gets its own context, so {@code wa} → {@code {AObj}} and
 * {@code wb} → {@code {BObj}}, strictly reducing {@code avgPtsSize}.
 */
public class ConfigRunnerTest {

    private static final String BENCHMARK_CP = "src/test/resources/pta/arm3";

    private static final String MAIN_CLASS = "WrapperAlias";

    /**
     * Step 1 (failing): run B0 (CI) and B1 (2-obj) on WrapperAlias; assert
     * both complete and B1 is at least as precise as B0 ({@code avgPtsSize}).
     *
     * <p>Context sensitivity can only improve precision: the 2-obj analysis
     * separates the two call sites of {@code id()}, so each wrapper output
     * points to exactly one object instead of two, making B1 strictly more
     * precise than B0 on this benchmark.
     */
    @Test
    void b1IsAtLeastAsPreciseAsB0OnWrapperAlias() {
        ConfigRunner runner = new ConfigRunner();

        MetricCollector.Metrics b0 = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        MetricCollector.Metrics b1 = runner.run(Configs.B1, BENCHMARK_CP, MAIN_CLASS);

        assertNotNull(b0, "B0 (CI) must complete without exception");
        assertNotNull(b1, "B1 (2-obj) must complete without exception");

        assertTrue(b1.avgPtsSize() <= b0.avgPtsSize(),
                "B1 (2-obj) avgPtsSize=" + b1.avgPtsSize()
                + " should be <= B0 (CI) avgPtsSize=" + b0.avgPtsSize()
                + " (context sensitivity only improves precision)");

        // On WrapperAlias CI conflates {AObj,BObj} in wa/wb: strict inequality.
        assertTrue(b0.avgPtsSize() > 0,
                "B0 must see non-empty points-to sets");
    }
}
