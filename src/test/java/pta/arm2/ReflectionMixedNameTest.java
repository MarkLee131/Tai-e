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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A co-present (bogus) string constant must NOT suppress the LLM when the name var
 * also points to an Unknown string — the DaCapo findClass case where the pts is a
 * bag of parser constants plus a merged Unknown. arm② must still resolve the Unknown.
 */
public class ReflectionMixedNameTest {

    private static final LlmOracle ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "Mixed" : "", false, 0.0);

    @Test
    void constantDoesNotSuppressLlmForUnknownName() {
        LlmInferenceModel.setOracle(ORACLE);
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionMixedName",
                    "reflection-inference:llm;distinguish-string-constants:reflection"
                            + ";merge-string-objects:false");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(reachable.contains("Mixed.hit"),
                "the bogus constant \"args\" must not suppress the LLM for the Unknown name "
                        + "→ Mixed resolved → Mixed.<init> → hit() reachable; got " + reachable);
    }
}
