package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pta.llm.MockOracle;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: the arm② §11 pipeline (rich evidence + two-layer sound
 * disposer) wired into {@link LlmReflectionModel} drives the analysis end-to-end.
 * The downcast bound must clamp a type-valid-but-cast-inconsistent LLM proposal.
 */
public class LlmReflectionPipelineTest {

    private static final String PLUGIN = "plugins:[pta.arm2.LlmReflectionModel]";

    private static boolean reachable(String declClass, String method) {
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods().anyMatch(m ->
                m.getDeclaringClass().getName().equals(declClass)
                        && m.getName().equals(method));
    }

    /**
     * Oracle proposes Dog AND Cat for a newInstance() downcast to Animal.
     * The downcast bound admits Dog (a subtype) and clamps out Cat (not a
     * subtype): Dog.&lt;init&gt; becomes reachable, Cat.&lt;init&gt; does not.
     */
    @Test
    void downcastBoundClampsProposalsEndToEnd() {
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "Dog\nCat"));
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionClamp",
                    "reflection-inference:string-constant", PLUGIN);
            assertTrue(reachable("Dog", "<init>"),
                    "Dog <: Animal (downcast bound) → admitted → Dog.<init> reachable");
            assertFalse(reachable("Cat", "<init>"),
                    "Cat is not <: Animal → clamped by downcast bound → Cat.<init> NOT reachable");
        } finally {
            LlmReflectionModel.clearOracle();
        }
    }
}
