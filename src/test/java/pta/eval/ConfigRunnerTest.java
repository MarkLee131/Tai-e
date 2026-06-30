package pta.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link ConfigRunner} and {@link Configs}.
 *
 * <p>Uses {@code BoxAlias} from {@code src/test/resources/pta/eval} as the
 * canonical object-sensitivity benchmark.
 */
public class ConfigRunnerTest {

    private static final String BENCHMARK_CP = "src/test/resources/pta/eval";
    private static final String MAIN_CLASS = "BoxAlias";

    /**
     * B1 must achieve strictly smaller avgPtsSize than B0 (precision sanity).
     * RunResult.metrics() wraps MetricCollector.Metrics.
     */
    @Test
    void b1IsStrictlyMorePreciseThanB0OnBoxAlias() {
        ConfigRunner runner = new ConfigRunner();

        ConfigRunner.RunResult r0 = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        ConfigRunner.RunResult r1 = runner.run(Configs.B1, BENCHMARK_CP, MAIN_CLASS);

        assertNotNull(r0, "B0 (CI) must complete without exception");
        assertNotNull(r1, "B1 (2-obj) must complete without exception");

        assertTrue(r0.metrics().avgPtsSize() > 0,
                "B0 must see non-empty points-to sets");

        assertTrue(r1.metrics().avgPtsSize() < r0.metrics().avgPtsSize(),
                "B1 (2-obj) avgPtsSize=" + r1.metrics().avgPtsSize()
                + " must be < B0 (CI) avgPtsSize=" + r0.metrics().avgPtsSize());
    }

    /**
     * timeMs must be positive (wall-clock is measured and NOT discarded).
     */
    @Test
    void timeMsIsPositive() {
        ConfigRunner runner = new ConfigRunner();
        ConfigRunner.RunResult result = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        assertTrue(result.timeMs() > 0,
                "timeMs must be positive — analysis takes non-zero time");
    }

    /**
     * Baseline configs (no LLM) must have llmQueries == 0 and costUsd == 0.
     */
    @Test
    void baselineHasZeroCostAndZeroQueries() {
        ConfigRunner runner = new ConfigRunner();
        ConfigRunner.RunResult result = runner.run(Configs.B0, BENCHMARK_CP, MAIN_CLASS);
        assertEquals(0, result.llmQueries(),
                "Baseline B0 must have 0 LLM queries");
        assertEquals(0.0, result.costUsd(), 1e-12,
                "Baseline B0 must have 0.0 cost");
    }

    /**
     * Arm① run with a mock oracle must report llmQueries > 0.
     * Uses SweepBenchmark (in pta/eval) with the all-YES mock so that all
     * application methods are selected, guaranteeing at least one oracle call.
     */
    @Test
    void arm1RunWithMockReportsPositiveLlmQueries() {
        ConfigRunner runner = new ConfigRunner();
        String mockFile = "src/test/resources/pta/eval/sweep-arm1-allyes.txt";
        Configs.Config a1 = Configs.a1(mockFile);
        ConfigRunner.RunResult result = runner.run(a1, BENCHMARK_CP, "SweepBenchmark");
        assertTrue(result.llmQueries() > 0,
                "Arm① run with a mock oracle must make at least one LLM query; got "
                + result.llmQueries());
    }
}
