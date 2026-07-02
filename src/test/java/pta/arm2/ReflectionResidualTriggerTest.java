package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmOracle;
import pta.llm.LlmResponse;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3/S4/S6: the residual trigger and query latch of {@link LlmInferenceModel} must match
 * the formal residual predicate (⊤ ∈ Ŝ(n)) and stay monotone across solver iterations.
 */
public class ReflectionResidualTriggerTest {

    private static Set<String> run(String main, LlmOracle oracle) {
        LlmInferenceModel.setOracle(oracle);
        try {
            Tests.testPTA(false, "reflection", main,
                    "reflection-inference:llm;distinguish-string-constants:reflection"
                            + ";merge-string-objects:false");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            return r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
    }

    /** S3: a co-present distinguished constant must not suppress the LLM at getMethod. */
    @Test
    void constantDoesNotSuppressLlmAtGetMethod() {
        Set<String> reachable = run("ArmReflectionMixedMember", q -> new LlmResponse(
                "llm-method".equals(q.kind()) ? "realRun" : "", false, 0.0));
        assertTrue(reachable.contains("Target.decoy"),
                "the constant part must still resolve decoy(); got " + reachable);
        assertTrue(reachable.contains("Target.hit"),
                "S3: the Unknown part must reach the LLM despite the co-present constant "
                        + "→ realRun() → hit() reachable; got " + reachable);
    }

    /** S4: proposals must be re-injected for classes that arrive after the first query. */
    @Test
    void lateArrivingClassGetsProposals() {
        Set<String> reachable = run("ArmReflectionLateClass", q -> new LlmResponse(
                switch (q.kind()) {
                    case "llm-class" -> "Late";
                    case "llm-method" -> "go";
                    default -> "";
                }, false, 0.0));
        assertTrue(reachable.contains("Early.eHit"),
                "sanity: Early (first fire) must resolve go() → eHit(); got " + reachable);
        assertTrue(reachable.contains("Late.lHit"),
                "S4: Late arrives after the query — cached proposals must be re-injected "
                        + "→ Late.go() → lHit() reachable; got " + reachable);
    }

    /** S6: the resolved-probe must use the same declared-variant as the injection. */
    @Test
    void declaredProbeMatchesInjectionVariant() {
        run("ArmReflectionDeclaredProbe", q -> new LlmResponse(
                "llm-method".equals(q.kind()) ? "inherited" : "", false, 0.0));
        List<String> gaps = LlmInferenceModel.gapReport();
        assertTrue(gaps.stream().anyMatch(g -> g.contains("ArmReflectionDeclaredProbe")),
                "S6: getDeclaredMethod injected nothing (method is inherited, not declared) "
                        + "— the site must be flagged, not reported resolved; gapReport=" + gaps);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        assertFalse(r.getCallGraph().reachableMethods()
                        .map(m -> m.getSignature())
                        .anyMatch(s -> s.contains("Base: void inherited")),
                "sanity: getDeclaredMethods(Sub) must not resolve the inherited method");
    }
}
