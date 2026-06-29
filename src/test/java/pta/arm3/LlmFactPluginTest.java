package pta.arm3;

import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.exp.Var;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
