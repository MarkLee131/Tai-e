package pta.baseline.cafd;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.exp.Var;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link AllocatorWrapperModel} + the {@code advanced:cafd}
 * branch in {@link PointerAnalysis}.
 *
 * <p>Runs {@code WrapperProgram} under two configurations and asserts that
 * {@code advanced:cafd} is strictly more precise than {@code cs:ci}:
 * <ul>
 *   <li><b>#abstract objects ↑</b>: CAFD creates a distinct per-callsite
 *       MockObj for every call site of {@code make()}, so the total number
 *       of reachable abstract objects is larger than under CI.</li>
 *   <li><b>total PTS size ↓</b>: CAFD suppresses the body of wrapper methods
 *       (so their internal variables disappear from the analysis), and the
 *       per-callsite objects do not merge across call sites, giving a smaller
 *       total points-to sum across all variables.</li>
 * </ul>
 */
public class AllocatorWrapperModelTest {

    private static final String DIR = "cafd";
    private static final String MAIN = "WrapperProgram";

    /** Sum of points-to set sizes across all reachable variables. */
    private static long totalPointsTo(PointerAnalysisResult pta) {
        long sum = 0;
        for (Var v : pta.getVars()) {
            sum += pta.getPointsToSet(v).size();
        }
        return sum;
    }

    @Test
    void cafdProducesMoreObjectsAndSmallerTotalPts() {
        // Baseline: context-insensitive analysis
        Tests.testPTA(false, DIR, MAIN, "cs:ci");
        PointerAnalysisResult ciResult = World.get().getResult(PointerAnalysis.ID);
        int ciObjects = ciResult.getObjects().size();
        long ciPts = totalPointsTo(ciResult);

        // CAFD: allocator-wrapper heap refinement
        Tests.testPTA(false, DIR, MAIN, "advanced:cafd");
        PointerAnalysisResult cafdResult = World.get().getResult(PointerAnalysis.ID);
        int cafdObjects = cafdResult.getObjects().size();
        long cafdPts = totalPointsTo(cafdResult);

        // CAFD must produce more abstract objects than CI
        assertTrue(cafdObjects > ciObjects,
                String.format("CAFD (#obj=%d) must produce more abstract objects than CI (#obj=%d)"
                        + " — per-callsite cloning for make() should add 2 MockObjs vs 1 NewObj",
                        cafdObjects, ciObjects));

        // CAFD must strictly reduce total PTS size (observed CAFD=9 < CI=10):
        // suppressing make()'s body removes its internal-variable contributions
        assertTrue(cafdPts < ciPts,
                String.format("CAFD (pts-sum=%d) must be strictly less than CI (pts-sum=%d)"
                        + " — suppressing make() body removes its internal-variable contributions",
                        cafdPts, ciPts));
    }
}
