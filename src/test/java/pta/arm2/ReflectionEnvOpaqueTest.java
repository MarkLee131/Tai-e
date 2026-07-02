package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmResponse;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5: {@code getProperty(key, default)} must emit ⊤ at the SITE (once reachable), not only
 * when the default argument's points-to set is non-empty — otherwise the opaque-default
 * case loses the ⊤ exactly where it matters, and a co-present constant suppresses every
 * downstream residual path (no empty pts for the seeder, no Unknown obj for the trigger).
 */
public class ReflectionEnvOpaqueTest {

    @Test
    void opaqueDefaultStillEmitsTop() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "EnvTarget2" : "", false, 0.0));
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionEnvOpaque",
                    "reflection-inference:llm;distinguish-string-constants:reflection"
                            + ";merge-string-objects:false");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(reachable.contains("Decoy2.<init>"),
                "sanity: the constant default must resolve Decoy2; got " + reachable);
        assertTrue(reachable.contains("EnvTarget2.hit"),
                "S5: site-level ⊤ must reach forName despite the constant branch → LLM → "
                        + "EnvTarget2 reachable; got " + reachable);
    }
}
