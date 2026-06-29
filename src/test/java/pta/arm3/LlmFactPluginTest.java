package pta.arm3;

import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arm 3 integration: a defeasible LLM fact, once it survives the
 * {@link pta.arm3.datalog.ConsistencyEngine}, refines pointer-analysis results
 * through {@code Solver.addPointerFilter}. A contradictory fact is rejected and
 * results are identical to the unrefined baseline (soundness preserved).
 */
public class LlmFactPluginTest {

    private static final String DIR = "src/test/resources/pta/arm3";

    /** Runs CI pointer analysis (optionally with the plugin) and returns
     *  |pts(wa)| — wa is the identity-wrapper output that CI conflates. */
    private static int ptsSizeOfWa(String mockFile) {
        StringBuilder pta = new StringBuilder(
                "cs:ci;only-app:true;implicit-entries:false");
        if (mockFile != null) {
            pta.append(";plugins:[pta.arm3.LlmFactPlugin];llm-mock-file:")
               .append(mockFile);
        }
        Main.main("-cp", DIR, "-m", "WrapperAlias", "-a", "pta=" + pta);
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
        for (Var v : result.getVars()) {
            if (v.getName().equals("wa")
                    && v.getMethod().getName().equals("main")) {
                return result.getPointsToSet(v).size();
            }
        }
        throw new AssertionError("variable 'wa' not found in main");
    }

    @Test
    void goodFactReducesAliasSet() {
        int baseline = ptsSizeOfWa(null);
        assertEquals(2, baseline,
                "CI baseline conflates AObj and BObj in the wrapper output");
        int refined = ptsSizeOfWa(DIR + "/facts-good.txt");
        assertEquals(1, refined,
                "accepted never-alias(AObj,BObj) de-conflates wa to {AObj}");
    }

    @Test
    void contradictoryFactIsRejectedAndPreservesBaseline() {
        int baseline = ptsSizeOfWa(null);
        int withBadFact = ptsSizeOfWa(DIR + "/facts-bad.txt");
        assertEquals(baseline, withBadFact,
                "never-alias(AObj,AObj) contradicts reflexive base alias, "
                        + "is rejected, and leaves results unchanged");
    }

    /** Runs CI pointer analysis (optionally with the plugin) on
     *  MultiInflowWrapper and returns pts(lhs) — lhs is the identity-wrapper
     *  output whose argument has two inflows (group-A new + group-B return). */
    private static java.util.Set<Obj> ptsOfLhsMultiInflow(String mockFile) {
        StringBuilder pta = new StringBuilder(
                "cs:ci;only-app:true;implicit-entries:false");
        if (mockFile != null) {
            pta.append(";plugins:[pta.arm3.LlmFactPlugin];llm-mock-file:")
               .append(mockFile);
        }
        Main.main("-cp", DIR, "-m", "MultiInflowWrapper", "-a", "pta=" + pta);
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
        for (Var v : result.getVars()) {
            if (v.getName().equals("lhs")
                    && v.getMethod().getName().equals("main")) {
                return result.getPointsToSet(v);
            }
        }
        throw new AssertionError("variable 'lhs' not found in main");
    }

    private static boolean containsType(java.util.Set<Obj> pts, String simple) {
        return pts.stream().anyMatch(o -> {
            String n = o.getType().getName();
            int dot = n.lastIndexOf('.');
            return (dot < 0 ? n : n.substring(dot + 1)).equals(simple);
        });
    }

    /**
     * SOUNDNESS (C1): a wrong {@code never-alias A B} fact must NOT drop a real
     * object. The wrapper arg {@code arg} has multiple inflows — a group-A
     * {@code new} and a group-B method return (B {@code <:} A) — so the identity
     * wrapper output {@code lhs} legitimately holds BOTH an A-obj and a B-obj.
     * The disposer must WITHHOLD the never-alias filter (it cannot soundly prove
     * arg is exclusively group A), preserving the B-obj in pts(lhs) and the real
     * {@code lhs.foo() -> B.foo()} dispatch edge.
     *
     * <p>Against the buggy (syntactic-allocGroup) implementation this FAILS:
     * the B-obj is filtered out of pts(lhs). After the sound CI-gated fix it
     * PASSES.
     */
    @Test
    void multiInflowWrapperPreservesSoundness() {
        java.util.Set<Obj> baseline = ptsOfLhsMultiInflow(null);
        assertEquals(2, baseline.size(),
                "CI baseline: pts(lhs) holds both the A-obj and the B-obj");
        assertTrue(containsType(baseline, "A") && containsType(baseline, "B"),
                "CI baseline pts(lhs) must contain both A and B objects");

        java.util.Set<Obj> withBadFact =
                ptsOfLhsMultiInflow(DIR + "/facts-multiinflow.txt");
        assertTrue(containsType(withBadFact, "B"),
                "SOUNDNESS: the bad never-alias(A,B) fact must NOT drop the real "
                        + "B-obj from pts(lhs) — arg has a group-B inflow, so the "
                        + "filter must be withheld (gated by the sound CI pts of arg)");
        assertEquals(2, withBadFact.size(),
                "pts(lhs) must remain {A-obj, B-obj}: a wrong LLM fact may cost "
                        + "precision but must never remove a real object");
    }
}
