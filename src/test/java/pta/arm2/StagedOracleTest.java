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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Staged querying (B-wave): during solving the model only latches residual
 * sites; queries fire at onPhaseFinish against the CONVERGED state, so the
 * prompt is canonical (schedule-independent). Fire-once legacy behavior
 * remains under -Darm2.staged=false.
 */
public class StagedOracleTest {

    private final List<String> promptLog = new ArrayList<>();

    @AfterEach
    void tearDown() {
        System.clearProperty("arm2.staged");
        LlmInferenceModel.clearOracle();
    }

    private Set<String> run(String fixture) {
        LlmInferenceModel.setOracle(q -> {
            promptLog.add(q.contextId() + "|" + q.prompt());
            return new LlmResponse(
                    "llm-class".equals(q.kind()) ? "ClampBase" : "", false, 0.0);
        });
        Tests.testPTA(false, "reflection", fixture, "reflection-inference:llm");
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void stagedModeStillResolvesTheFixture() {
        System.setProperty("arm2.staged", "true");
        Set<String> reachable = run("ArmReflectionCastClamp");
        assertTrue(reachable.contains("ClampA.aHit"),
                "staged mode must reach the same resolution as legacy; got " + reachable);
        assertFalse(reachable.contains("ClampB.bHit"), "fence must hold in staged mode");
        assertFalse(promptLog.isEmpty(), "oracle must have been consulted");
    }

    @Test
    void legacyModeStillWorks() {
        System.setProperty("arm2.staged", "false");
        Set<String> reachable = run("ArmReflectionCastClamp");
        assertTrue(reachable.contains("ClampA.aHit"));
    }

    @Test
    void stagedPromptIsBuiltFromConvergedState() {
        // The staged prompt is a function of the CONVERGED phase state only, so
        // every prompt for the same site must be identical across two runs
        // (canonical prompts are the determinism fix's core claim).
        System.setProperty("arm2.staged", "true");
        run("ArmReflectionCastClamp");
        List<String> first = List.copyOf(promptLog);
        promptLog.clear();
        run("ArmReflectionCastClamp");
        assertEquals(first, promptLog,
                "staged prompts must be canonical (identical across runs)");
    }
}
