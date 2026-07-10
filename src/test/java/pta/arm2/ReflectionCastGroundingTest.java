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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 use-site type grounding (the gruntspud {@code PluginManager.loadPlugins} shape,
 * in vitro): when the reflectively loaded class is locally instantiated and downcast
 * — {@code (T) Class.forName(<unknown>).newInstance()} — the prompt must state the
 * cast type and enumerate its concrete classpath subtypes (sorted, canonical). This
 * is the deterministic site-relevance signal whose loss (B2b alphabetical slot
 * canonicalization) flipped gruntspud's plugin-site answer and collapsed recall to
 * the string-constant floor; see eval/arm2/raw/staged/staged-vs-legacy-rq2367.md.
 */
public class ReflectionCastGroundingTest {

    private final List<String> prompts = new ArrayList<>();

    @AfterEach
    void tearDown() {
        System.clearProperty("arm2.staged");
        LlmInferenceModel.clearOracle();
    }

    /** Runs the fixture in the given pipeline; returns the class-site prompt. */
    private String runAndCapturePrompt(boolean staged) {
        prompts.clear();
        System.setProperty("arm2.staged", String.valueOf(staged));
        LlmInferenceModel.setOracle(q -> {
            if ("llm-class".equals(q.kind())) {
                prompts.add(q.prompt());
                return new LlmResponse("CastGroundImplA", false, 0.0);
            }
            return new LlmResponse("", false, 0.0);
        });
        Tests.testPTA(false, "reflection", "ArmReflectionCastGrounding",
                "reflection-inference:llm");
        assertEquals(1, prompts.size(),
                "exactly one class-site query expected; got " + prompts);
        return prompts.get(0);
    }

    @Test
    void castGroundingSlotInStagedPrompt() {
        String prompt = runAndCapturePrompt(true);
        assertTrue(prompt.contains("cast to CastGroundPlugin at the use site"),
                "prompt must state the use-site downcast type; got:\n" + prompt);
        assertTrue(prompt.contains("[CastGroundImplA, CastGroundImplB]"),
                "prompt must enumerate the concrete subtypes sorted; got:\n" + prompt);
        // End-to-end: the grounded answer resolves and the cascade starts.
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        Set<String> reachable = r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
        assertTrue(reachable.contains("CastGroundImplA.start"),
                "injected subtype's method must become reachable; got " + reachable);
    }

    @Test
    void castGroundingPromptIdenticalAcrossPipelines() {
        // The staged/legacy pipelines must build byte-identical prompts: every prompt
        // input is IR/classpath-derived, never points-to-schedule-derived. (This is the
        // property that exonerated the staged deferral in the gruntspud regression.)
        String stagedPrompt = runAndCapturePrompt(true);
        String legacyPrompt = runAndCapturePrompt(false);
        assertEquals(stagedPrompt, legacyPrompt,
                "prompt bytes must not depend on the pipeline mode");
    }
}
