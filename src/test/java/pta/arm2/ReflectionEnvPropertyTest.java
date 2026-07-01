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
 * G7: {@code System.getProperty(key, default)} may return a runtime-set value, not just
 * the default. SelfInferenceModel must emit {@code default ⊔ ⊤} so a downstream
 * {@code Class.forName} routes the Unknown (⊤) to the oracle — otherwise the env-set
 * class is silently missed (under-approximation).
 */
public class ReflectionEnvPropertyTest {

    private static final LlmOracle ENV_ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "EnvTarget" : "", false, 0.0);

    @Test
    void getPropertyDefaultRoutesRuntimeValueToOracle() {
        LlmInferenceModel.setOracle(ENV_ORACLE);
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionEnvProperty",
                    "reflection-inference:llm");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
        // add-only soundness: the default is still resolved.
        assertTrue(reachable.contains("DefaultImpl.base"),
                "the default DefaultImpl must still be resolved (add-only); got " + reachable);
        // G7: the ⊤ routes forName to the oracle, so the env-set class is recovered.
        assertTrue(reachable.contains("EnvTarget.hit"),
                "getProperty(key,default) must emit ⊤ so the runtime-set EnvTarget is recovered "
                        + "via the oracle (not under-approximated to the default); got " + reachable);
    }
}
