package pta.arm2;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE smoke for arm② (opt-in: {@code ./gradlew test -ParmLive --tests
 * pta.arm2.ReflectionLiveSmokeTest}). Makes REAL Gemini calls via
 * {@link pta.llm.ApiKeyResolver} (no mock oracle), to verify the real model's
 * free-text answer parses into a loadable class/method name and resolves the
 * invoke chain end-to-end. Skipped by default (no API cost in CI).
 */
public class ReflectionLiveSmokeTest {

    @Test
    void liveGeminiResolvesInvokeChain() {
        Assumptions.assumeTrue(Boolean.getBoolean("arm2.live"),
                "live smoke disabled — run with -ParmLive to enable real Gemini calls");
        LlmInferenceModel.clearOracle(); // ensure the live ApiKeyResolver path is used
        Tests.testPTA(false, "reflection", "ArmReflectionInvoke", "reflection-inference:llm");
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        boolean greetReachable = r.getCallGraph().reachableMethods().anyMatch(m ->
                m.getDeclaringClass().getName().equals("GreeterImpl")
                        && m.getName().equals("greet"));
        assertTrue(greetReachable,
                "live Gemini must resolve forName/getMethod so GreeterImpl.greet is reachable");
    }
}
