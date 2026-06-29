package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pta.llm.MockOracle;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Arm 2: LLM reflection target resolution with the sound
 * {@link TargetFilter} gate.
 * <p>
 * Demonstrates two properties on a program whose reflective target is missed
 * by {@code reflection-inference:string-constant}:
 * <ul>
 *     <li><b>Recall gain</b>: a correct oracle makes the previously-missing
 *         call edge to {@code Service.<init>} appear.</li>
 *     <li><b>Soundness</b>: a garbage oracle adds no spurious edge (the filter
 *         drops it), so results equal the baseline.</li>
 * </ul>
 */
public class LlmReflectionTest {

    private static final String DIR = "reflection";

    private static final String MAIN = "LlmReflection";

    private static final String PLUGIN = "plugins:[pta.arm2.LlmReflectionModel]";

    /**
     * @return whether {@code Service.<init>} is reachable in the resulting
     * call graph after running the analysis with the given options.
     */
    private static boolean serviceInitReachable(String... opts) {
        Tests.testPTA(false, DIR, MAIN, opts);
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);
        CallGraph<Invoke, JMethod> cg = result.getCallGraph();
        return cg.reachableMethods().anyMatch(m ->
                m.getDeclaringClass().getName().equals("Service")
                        && m.getName().equals("<init>"));
    }

    @Test
    void baselineMissesReflectiveTarget() {
        // Without the LLM arm, the non-constant class name is unresolved.
        assertFalse(serviceInitReachable("reflection-inference:string-constant"),
                "baseline string-constant inference must miss Service.<init>");
    }

    @Test
    void goodOracleRecoversMissingEdge() {
        // Oracle proposes the correct class for every site (default answer).
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "Service"));
        try {
            assertTrue(serviceInitReachable(
                            "reflection-inference:string-constant", PLUGIN),
                    "with a correct oracle the missing Service.<init> edge must appear");
        } finally {
            LlmReflectionModel.clearOracle();
        }
    }

    @Test
    void garbageOracleAddsNoSpuriousEdge() {
        // Oracle proposes a class that is not loaded -> filter drops it.
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "no.such.Class$$$"));
        try {
            assertFalse(serviceInitReachable(
                            "reflection-inference:string-constant", PLUGIN),
                    "a garbage oracle must not add any edge (soundness)");
        } finally {
            LlmReflectionModel.clearOracle();
        }
    }
}
