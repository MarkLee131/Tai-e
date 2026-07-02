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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant guard for the formal model's §6 Clamp: the use-site bound (the downcast on
 * the newInstance result) is enforced by Tai-e's ACTION-site machinery
 * (ReflectiveActionModel + TypeMatcher) — a bound-violating subtype injected by arm②'s
 * expansion may exist as a spurious Class metaobject, but must never become an INSTANCE
 * (no constructor/method reachability). Verified empirically: this held with no
 * forName-site clamp needed (the P1 review finding's failure scenario does not
 * materialize at the reachable-method level), so no extra clamp code was added —
 * this test pins that invariant against regression.
 */
public class ReflectionCastClampTest {

    @Test
    void actionSiteMachineryEnforcesTheCastBound() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "ClampBase" : "", false, 0.0));
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionCastClamp",
                    "reflection-inference:llm");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(reachable.contains("ClampA.aHit"),
                "recall: the bound-conforming subtype ClampA must be instantiated → go() → "
                        + "aHit; got " + reachable);
        assertFalse(reachable.contains("ClampB.bHit"),
                "clamp invariant: ClampB violates the (INarrow) downcast bound — the action-"
                        + "site machinery must never instantiate it; got " + reachable);
    }
}
