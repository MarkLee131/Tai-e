package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2: providers recorded after the iterator()/next() handlers already fired must still be
 * delivered (monotone completion) — the provider set is not a one-shot snapshot.
 */
public class ServiceLoaderLateProviderTest {

    @Test
    void lateInterfaceProvidersReachTheLoop() {
        System.setProperty("arm2.configRoot", "src/test/resources/pta/reflection");
        System.setProperty("arm2.addons", "true"); // llm-mode-only by default (H2)
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionLateProvider",
                    "reflection-inference:string-constant");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            System.clearProperty("arm2.configRoot");
            System.clearProperty("arm2.addons");
        }
        assertTrue(reachable.contains("ProvA.aHit"),
                "sanity: the first-round interface SvcA must deliver ProvA; got " + reachable);
        assertTrue(reachable.contains("ProvB.bHit"),
                "S2: SvcB arrives after iterator()/next() fired — its provider ProvB must "
                        + "still be delivered (no stale snapshot); got " + reachable);
    }
}
