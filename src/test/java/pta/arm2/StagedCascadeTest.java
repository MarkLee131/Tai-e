package pta.arm2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-phase staged resolution (B-wave, the antlr shape in vitro): site B's
 * enclosing method ({@code Stage1.go}) becomes reachable ONLY via site A's
 * injection, and injections happen exclusively at phase boundaries — so site B
 * can only be latched in a LATER phase than site A's injection. One staged run
 * must resolve both: phase 1 latches site A; phase-finish 1 queries and injects
 * Stage1; phase 2 reaches Stage1.go and latches site B; phase-finish 2 queries
 * and injects Stage2; phase 3 propagates Stage2.leaf. This pins the contract
 * that {@code pending} persists across phases and the phase loop re-enters
 * while injections add work.
 */
public class StagedCascadeTest {

    private final List<String> queryLog = new ArrayList<>();

    @AfterEach
    void tearDown() {
        System.clearProperty("arm2.staged");
        LlmInferenceModel.clearOracle();
    }

    @Test
    void twoStageCascadeResolvesAcrossPhases() {
        System.setProperty("arm2.staged", "true");
        LlmInferenceModel.setOracle(q -> {
            queryLog.add(q.contextId());
            if ("llm-class".equals(q.kind())) {
                if (q.contextId().contains("main(")) {
                    return new LlmResponse("Stage1", false, 0.0);
                }
                if (q.contextId().contains("go()")) {
                    return new LlmResponse("Stage2", false, 0.0);
                }
            }
            return new LlmResponse("", false, 0.0);
        });
        Tests.testPTA(false, "reflection", "ArmReflectionCascade",
                "reflection-inference:llm");
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        Set<String> reachable = r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
        assertTrue(reachable.contains("Stage1.go"),
                "phase-finish 1 must inject Stage1, making Stage1.go reachable; got "
                        + reachable);
        assertTrue(reachable.contains("Stage2.leaf"),
                "phase-finish 2 must inject Stage2 (site B is only discoverable AFTER "
                        + "site A's injection), making Stage2.leaf reachable; got "
                        + reachable);
        // The oracle must have seen BOTH sites. Site B's container is unreachable
        // until site A's phase-boundary injection, so its query is proof of a
        // second staged phase.
        assertTrue(queryLog.stream().anyMatch(c -> c.contains("main(")),
                "site A (in main) must have been queried; log: " + queryLog);
        assertTrue(queryLog.stream().anyMatch(c -> c.contains("go()")),
                "site B (in Stage1.go) must have been queried — requires a second "
                        + "phase; log: " + queryLog);
    }
}
