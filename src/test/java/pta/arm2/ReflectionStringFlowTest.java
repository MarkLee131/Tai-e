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
 * Scenario B: reflective names built via StringBuilder → empty points-to → the
 * pts-driven handler never fires. arm②'s empty-pts path must backward-trace the
 * constant string fragments as context and resolve via the LLM.
 */
public class ReflectionStringFlowTest {

    private static final LlmOracle ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "WidgetImpl" : "render", false, 0.0);

    private static Set<String> reachable(String main, String... opts) {
        Tests.testPTA(false, "reflection", main, opts);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void resolvesEmptyPtsStringBuiltNames() {
        Set<String> baseline = reachable("ArmReflectionStringFlow",
                "reflection-inference:string-constant");
        assertFalse(baseline.contains("WidgetImpl.render"),
                "non-vacuity: baseline must miss WidgetImpl.render");

        LlmInferenceModel.setOracle(ORACLE);
        Set<String> withArm;
        try {
            withArm = reachable("ArmReflectionStringFlow", "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(withArm.contains("WidgetImpl.render"),
                "arm② empty-pts path must resolve the StringBuilder-built names → WidgetImpl.render reachable; got "
                        + withArm);
        assertTrue(withArm.contains("WidgetImpl.tag"),
                "the invoke edge must fire so render()'s body is analyzed → tag() reachable");
        assertTrue(withArm.containsAll(baseline),
                "SOUNDNESS: add-only — must not drop baseline-reachable methods");
    }
}
