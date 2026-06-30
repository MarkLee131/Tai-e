package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pta.llm.MockOracle;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capstone functional test for arm②'s core guarantee: the arm is <em>add-only</em>
 * — enabling it never drops a baseline-reachable method (soundness) — while it
 * recovers reflective targets the baseline misses (recall gain). Compared by
 * method signature across two World builds.
 */
public class ReflectionSoundnessTest {

    private static final String PLUGIN = "plugins:[pta.arm2.LlmReflectionModel]";

    private static Set<String> reachableSignatures() {
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getSignature())
                .collect(Collectors.toSet());
    }

    private static boolean hasServiceInit(Set<String> sigs) {
        return sigs.stream().anyMatch(s -> s.contains("Service") && s.contains("<init>"));
    }

    @Test
    void armIsAddOnlyAndRecoversReflectiveTarget() {
        // Baseline (no arm): string-constant inference cannot resolve the target.
        Tests.testPTA(false, "reflection", "LlmReflection",
                "reflection-inference:string-constant");
        Set<String> baseline = reachableSignatures();

        // With the arm and a correct oracle.
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "Service"));
        Set<String> withArm;
        try {
            Tests.testPTA(false, "reflection", "LlmReflection",
                    "reflection-inference:string-constant", PLUGIN);
            withArm = reachableSignatures();
        } finally {
            LlmReflectionModel.clearOracle();
        }

        assertTrue(withArm.containsAll(baseline),
                "SOUNDNESS: arm② is add-only — every baseline-reachable method must "
                        + "remain reachable; missing: "
                        + baseline.stream().filter(s -> !withArm.contains(s)).limit(5).toList());
        assertFalse(hasServiceInit(baseline),
                "non-vacuity: baseline must MISS Service.<init>");
        assertTrue(hasServiceInit(withArm),
                "RECALL: arm② must recover the missing Service.<init> edge");
    }
}
