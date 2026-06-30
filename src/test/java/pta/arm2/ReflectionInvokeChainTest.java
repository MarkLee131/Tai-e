package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional test for arm②'s SOUND invoke chain (forName → getMethod → invoke)
 * via metaobject injection into Tai-e's reflection stack: the LLM resolves the
 * Unknown class/method names, arm② injects the metaobjects, and
 * {@code ReflectiveActionModel} builds the invoke edge (with arg flow).
 */
public class ReflectionInvokeChainTest {

    /** Oracle keyed by query kind: class name for forName, method name for getMethod. */
    private static final LlmOracle KIND_ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "GreeterImpl" : "greet", false, 0.0);

    private static Set<String> reachable(String main, String... opts) {
        Tests.testPTA(false, "reflection", main, opts);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void llmResolvesInvokeChainSoundly() {
        // Baseline: non-constant names → greet() unreachable.
        Set<String> baseline = reachable("ArmReflectionInvoke",
                "reflection-inference:string-constant");
        assertFalse(baseline.contains("GreeterImpl.greet"),
                "non-vacuity: baseline must miss GreeterImpl.greet");

        // With arm② (reflection-inference:llm) + an oracle supplying the names.
        LlmInferenceModel.setOracle(KIND_ORACLE);
        Set<String> withArm;
        try {
            withArm = reachable("ArmReflectionInvoke", "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(withArm.contains("GreeterImpl.greet"),
                "arm② must resolve the invoke target GreeterImpl.greet; got reachable=" + withArm);
        assertTrue(withArm.contains("GreeterImpl.sink"),
                "the invoke edge must fire so greet()'s body is analyzed → sink() reachable");
        assertTrue(withArm.containsAll(baseline),
                "SOUNDNESS: arm② is add-only — must not drop baseline-reachable methods");
    }
}
