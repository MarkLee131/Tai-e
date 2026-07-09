package pta.arm2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmResponse;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experimental instantiation of the ∀-oracle theorems (T1a preservation, confinement)
 * at fixture scale: a CORRECT mock oracle is wrapped by property-driven corruption,
 * and the analysis must (silent) lose recall but never crash, (random) reject every
 * unloadable proposal via Φ, (fixed:ClampB) never instantiate a fence-violating class.
 */
public class AdversarialOracleTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("arm2.corrupt");
        System.clearProperty("arm2.corruptRate");
        System.clearProperty("arm2.corruptSeed");
        LlmInferenceModel.clearOracle();
    }

    private static Set<String> analyzeCastClampFixture() {
        Tests.testPTA(false, "reflection", "ArmReflectionCastClamp",
                "reflection-inference:llm");
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void silentCorruptionDropsRecallButAnalysisCompletes() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "ClampBase" : "", false, 0.0));
        System.setProperty("arm2.corrupt", "silent");
        Set<String> reachable = analyzeCastClampFixture();
        assertFalse(reachable.contains("ClampA.aHit"),
                "silent corruption must suppress the oracle's (correct) answer; got " + reachable);
        assertFalse(reachable.contains("ClampB.bHit"));
    }

    @Test
    void randomCorruptionIsFullyPhiRejected() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "ClampBase" : "", false, 0.0));
        System.setProperty("arm2.corrupt", "random");
        Set<String> reachable = analyzeCastClampFixture();
        assertFalse(reachable.contains("ClampA.aHit"));
        assertFalse(reachable.contains("ClampB.bHit"));
        // disposerStats() = "proposed,phiRejected,injected"
        String[] ds = LlmInferenceModel.disposerStats().split(",");
        assertTrue(Integer.parseInt(ds[0]) > 0, "oracle must have been consulted");
        assertEquals(ds[0], ds[1], "every unloadable garbage proposal must be Φ-rejected");
        assertEquals("0", ds[2], "nothing may be injected from unloadable garbage");
    }

    @Test
    void fixedLoadableWrongClassIsFenceClamped() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "ClampBase" : "", false, 0.0));
        System.setProperty("arm2.corrupt", "fixed:ClampB");
        Set<String> reachable = analyzeCastClampFixture();
        assertFalse(reachable.contains("ClampB.bHit"),
                "adversarial loadable-but-fence-violating proposal must never be "
                        + "instantiated (confinement); got " + reachable);
        assertFalse(reachable.contains("ClampA.aHit"),
                "the correct answer was corrupted away — recall must be lost, not faked");
    }
}
