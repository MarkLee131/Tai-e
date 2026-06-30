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
 * Functional test for arm②'s SOUND reflective field-get chain
 * (forName → getField → Field.get): the LLM resolves the Unknown class/field
 * names, arm② injects the metaobjects, and {@code ReflectiveActionModel} models
 * the field read so the field value flows.
 */
public class ReflectionFieldChainTest {

    private static final LlmOracle ORACLE = q -> new LlmResponse(
            switch (q.kind()) {
                case "llm-class" -> "Holder";
                case "llm-field" -> "payload";
                default -> "";
            }, false, 0.0);

    private static Set<String> reachable(String main, String... opts) {
        Tests.testPTA(false, "reflection", main, opts);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void llmResolvesFieldGetChainSoundly() {
        Set<String> baseline = reachable("ArmReflectionField",
                "reflection-inference:string-constant");
        assertFalse(baseline.contains("Payload.run"),
                "non-vacuity: baseline must miss Payload.run");

        LlmInferenceModel.setOracle(ORACLE);
        Set<String> withArm;
        try {
            withArm = reachable("ArmReflectionField", "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(withArm.contains("Payload.run"),
                "arm② must resolve the field get so the Payload value flows → Payload.run reachable; got "
                        + withArm);
        assertTrue(withArm.containsAll(baseline),
                "SOUNDNESS: arm② is add-only — must not drop baseline-reachable methods");
    }
}
