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
 * {@code BRunner} both implementing {@code Runner}, and two wrapper patterns:
 * <ul>
 *   <li><b>Instance wrapper</b> {@code RunnerBox.get()} — an instance method
 *       whose receiver ({@code box1} vs {@code box2} allocation sites) provides
 *       the 2-object heap context discriminator.  Under 2-obj (arm ① all-YES):
 *       {@code wa→{ARunner}}, {@code wb→{BRunner}} (pts size 1 each).
 *       Under CI (arm ① all-NO fallback): {@code wa→{ARunner,BRunner}},
 *       {@code wb→{ARunner,BRunner}} (pts size 2 each).  This precision gap
 *       makes the sound arm ① test non-vacuous.</li>
 *   <li><b>Static wrapper</b> {@code id(Runner r)} — a static method NOT
 *       context-separated by 2-obj.  Retained for the
 *       {@link UnsoundCafdStylePlugin} contrast test, which uses corrupt oracle
 *       {@code "wrapper id\nnever-alias ARunner ARunner"} to drop a call edge.</li>
 * </ul>
 *
 * <h2>Sound arm ① property</h2>
 * <p>Ground truth = 2-obj edges (p=0, all-YES oracle via {@code RunnerBox} path).
 * CI edges (any p&gt;0 where methods fall back to CI) always ⊇ 2-obj edges.
 * Recall = 1.0 at every error rate. Non-vacuity: {@code avgPtsSize} at p=1 (CI)
 * strictly exceeds that at p=0 (2-obj), proving the sweep exercises a real
 * precision effect while soundness held.
 *
 * <h2>Unsound CAFD-style property</h2>
 * <p>Ground truth = CI edges (p=0, empty oracle, no filtering) — 6 virtual-dispatch
 * edges total. At p=1, the corrupt oracle returns
 * {@code "wrapper id\nnever-alias ARunner ARunner"}, a self-contradictory fact.
 * The unsound plugin applies it without a consistency check: ARunner objects are
 * filtered from {@code xa} (result of {@code id(a)}), causing {@code xa.run()}
 * to miss {@code ARunner.run()} → recall ≈ 0.83 &lt; 1.0.
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
     * E_gt = 2-obj call edges (RunnerBox.get() is context-separated: wa→{ARunner},
     * wb→{BRunner}). At any p&gt;0, corrupted YES→NO answers reduce CS coverage;
     * those methods fall back to CI, which is a sound over-approximation.
     * CI edges ⊇ 2-obj edges → recall = 1.0.
     *
     * <p>Non-vacuity: the test additionally asserts that {@code avgPtsSize} at
     * p=1 (CI) is strictly greater than at p=0 (2-obj), confirming that the
     * corruption actually degraded precision while soundness was maintained.
     * Under 2-obj, {@code wa} and {@code wb} each point to a single runner
     * ({@code ARunner} or {@code BRunner}); under CI both point to
     * {@code {ARunner, BRunner}}, increasing {@code avgPtsSize}.
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

        // NON-VACUITY assertion: the sweep must have actually changed the analysis.
        // p=0 uses all-YES oracle (2-obj for all methods) — smaller pts sets.
        // p=1 uses all-NO oracle (CI for all methods) — larger pts sets.
        // CI is strictly less precise than 2-obj on SweepBenchmark: under CI,
        // pts(wa) = pts(wb) = {ARunner, BRunner} (size 2), but under 2-obj
        // pts(wa) = {ARunner}, pts(wb) = {BRunner} (size 1 each).
        // avgPtsSize at p=1 must strictly exceed avgPtsSize at p=0.
        RobustnessSweep.SweepPoint p0 = points.get(0);
        RobustnessSweep.SweepPoint pLast = points.get(points.size() - 1);
        assertTrue(pLast.m().avgPtsSize() > p0.m().avgPtsSize(),
                "Precision must degrade at p=1 (CI) vs p=0 (2-obj): "
                + "avgPtsSize at p=1 (" + pLast.m().avgPtsSize()
                + ") must strictly exceed avgPtsSize at p=0 ("
                + p0.m().avgPtsSize()
                + "). If equal, SweepBenchmark does not exhibit a precision gap "
                + "and must be enlarged.");
    }

    /**
     * Unsound CAFD-style config: recall must drop under high error rate.
     *
     * <p>p=0 oracle (empty response) → no wrapper/never-alias facts → no
     * filtering → E_gt = all CI virtual-dispatch edges (6 edges: wa.run() and
     * wb.run() each dispatch to both ARunner and BRunner via RunnerBox; xa.run()
     * and xb.run() each dispatch to ARunner and BRunner via static id()).
     *
     * <p>At p=1, the corrupt oracle returns
     * {@code "wrapper id\nnever-alias ARunner ARunner"} — a self-contradictory
     * fact that arm ③'s ConsistencyEngine would reject. The unsound plugin
     * applies it without any check: ARunner objects are filtered from {@code xa}
     * (result of {@code id(a)}, whose arg-group is ARunner), causing
     * {@code xa.run()} to miss ARunner.run() → 1 edge dropped, recall ≈ 0.83
     * &lt; 1.0.
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
