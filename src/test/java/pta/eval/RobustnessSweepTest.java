package pta.eval;

import org.junit.jupiter.api.Test;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;
import pta.llm.MockOracle;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link RobustnessSweep}.
 *
 * <h2>Thesis under test</h2>
 * <p>Our sound-by-construction arms (arm ①) maintain recall=1.0 at every LLM
 * error rate: wrong oracle answers degrade precision but never drop a real call
 * edge. The {@link UnsoundCafdStylePlugin} deliberately represents a CAFD-style
 * "LLM on the soundness critical path" design; its recall falls as the error
 * rate rises — proving the contrast.
 *
 * <h2>Benchmark: SweepBenchmark</h2>
 * <p>{@code SweepBenchmark} defines two runner classes {@code ARunner} and
 * {@code BRunner} both implementing {@code Runner}, and an identity wrapper
 * {@code id(Runner r)}. Under CI analysis, both {@code wa = id(a)} and
 * {@code wb = id(b)} have pts = {ARunner, BRunner}, producing 4 virtual-dispatch
 * edges. Under 2-obj (arm ① with all-YES oracle), the two allocation sites
 * separate: wa→{ARunner}, wb→{BRunner}, producing only 2 edges.
 *
 * <h2>Sound arm ① property</h2>
 * <p>Ground truth = 2-obj edges (p=0). CI edges (any p&gt;0 where some methods
 * are deselected) always ⊇ 2-obj edges. Recall = 1.0 ✓.
 *
 * <h2>Unsound CAFD-style property</h2>
 * <p>Ground truth = CI edges (p=0, empty oracle, no filtering). At p=1, the
 * corrupt oracle returns {@code "wrapper id\nnever-alias ARunner ARunner"}, a
 * self-contradictory fact. The unsound plugin applies it without checking,
 * filtering ARunner objects from wa → wa.run() can no longer dispatch to
 * ARunner.run() → recall &lt; 1.0.
 */
public class RobustnessSweepTest {

    private static final String BENCHMARK_CP = "src/test/resources/pta/eval";
    private static final String MAIN = "SweepBenchmark";
    /** Path to the all-YES mock oracle (selects all methods for CS at p=0). */
    private static final String ARM1_MOCK =
            "src/test/resources/pta/eval/sweep-arm1-allyes.txt";

    /**
     * Sound arm ①: recall must stay 1.0 at every error rate.
     *
     * <p>p=0 oracle (all YES) → all application methods selected for 2-obj →
     * E_gt = 2-obj call edges. At any p&gt;0, corrupted YES→NO answers reduce
     * CS coverage; those methods fall back to CI, which is a sound
     * over-approximation. CI edges ⊇ 2-obj edges → recall = 1.0.
     *
     * <p>The test is non-tautological: the p=0 (2-obj) call graph has FEWER
     * edges than the CI call graph that appears at p=1, yet recall stays 1.0
     * because every 2-obj edge is a projection of a CI edge (CI ⊇ 2-obj).
     */
    @Test
    void soundArm1RecallStaysOneAcrossAllErrorRates() {
        RobustnessSweep sweep = new RobustnessSweep();
        Configs.Config arm1 = Configs.a1(ARM1_MOCK);

        // Base oracle: loaded from mock file (default YES → selects all methods for CS)
        LlmOracle base = pta.arm1.ArmOracleFactory.loadMockOracle(
                java.nio.file.Path.of(ARM1_MOCK));
        // Corrupt: flip YES → NO (deselects methods, falls back to CI → more conservative)
        Function<LlmResponse, String> corrupt = r -> {
            List<String> lines = r.asLines();
            if (!lines.isEmpty() && lines.get(0).equalsIgnoreCase("YES")) {
                return "NO";
            }
            return "YES";
        };

        List<RobustnessSweep.SweepPoint> points = sweep.run(
                arm1, RobustnessSweep.DEFAULT_ERROR_RATES, BENCHMARK_CP, MAIN, base, corrupt);

        assertEquals(RobustnessSweep.DEFAULT_ERROR_RATES.length, points.size(),
                "sweep must return one SweepPoint per error rate");

        for (RobustnessSweep.SweepPoint pt : points) {
            assertEquals(1.0, pt.recall(), 1e-9,
                    "Arm① (sound-by-construction) must have recall=1.0 at errorRate="
                    + pt.errorRate() + " — wrong CS selection degrades precision but "
                    + "never drops a real call edge (CI ⊇ 2-obj always)");
        }
    }

    /**
     * Unsound CAFD-style config: recall must drop under high error rate.
     *
     * <p>p=0 oracle (empty response) → no wrapper/never-alias facts → no
     * filtering → E_gt = all CI virtual-dispatch edges (4 edges from wa.run()
     * and wb.run() dispatching to both ARunner.run() and BRunner.run()).
     *
     * <p>At p=1, the corrupt oracle returns
     * {@code "wrapper id\nnever-alias ARunner ARunner"} — a self-contradictory
     * fact that arm ③'s ConsistencyEngine would reject. The unsound plugin
     * applies it without any check: the filter removes ARunner objects from
     * {@code wa}'s points-to set, causing {@code wa.run()} to miss
     * ARunner.run() → recall = 0.75 &lt; 1.0.
     *
     * <p>This is NOT tautological: the drop is caused by a genuine soundness
     * violation in the plugin design, not by test construction.
     */
    @Test
    void unsoundCafdStyleLosesRecallUnderHighErrorRate() {
        RobustnessSweep sweep = new RobustnessSweep();

        // Base oracle: always returns "" — no facts, no filtering at p=0.
        LlmOracle base = new MockOracle(Map.of(), "");
        // Corrupt: inject self-contradictory "never-alias ARunner ARunner".
        // Arm③ would REJECT this (ConsistencyEngine: alias(ARunner,ARunner) exists).
        // UnsoundCafdStylePlugin ACCEPTS it without checking → drops ARunner from wa.
        Function<LlmResponse, String> corrupt =
                r -> "wrapper id\nnever-alias ARunner ARunner";

        Configs.Config unsound = RobustnessSweep.unsoundCafdConfig();
        List<RobustnessSweep.SweepPoint> points = sweep.run(
                unsound, RobustnessSweep.DEFAULT_ERROR_RATES,
                BENCHMARK_CP, MAIN, base, corrupt);

        assertEquals(RobustnessSweep.DEFAULT_ERROR_RATES.length, points.size());

        double recallAtZero = points.get(0).recall();
        double recallAtOne  = points.get(points.size() - 1).recall();

        assertEquals(1.0, recallAtZero, 1e-9,
                "CAFD-style at p=0 (empty oracle, no filtering) must have recall=1.0");
        assertTrue(recallAtOne < 1.0,
                "CAFD-style at p=1.0 (corrupt oracle: never-alias ARunner ARunner "
                + "applied without consistency check) must have recall < 1.0. Got: "
                + recallAtOne);
    }
}
