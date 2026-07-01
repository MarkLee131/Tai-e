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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * arm② must feed the application identity (an out-of-band fact the analysis knows but
 * the method body does not) into the LLM prompt, so the LLM can propose a
 * convention-driven class name. The mock oracle resolves the target ONLY when the
 * identity marker reaches the prompt — so the test passes only if the context is fed.
 */
public class ReflectionAppContextTest {

    /** Resolves Gadget only if the app-identity marker made it into the prompt. */
    private static final LlmOracle IDENTITY_ORACLE = q -> new LlmResponse(
            q.prompt().contains("GADGET-APP") ? "Gadget" : "", false, 0.0);

    private static Set<String> reachableWithContext(String appContext) {
        if (appContext != null) {
            System.setProperty("arm2.appContext", appContext);
        }
        LlmInferenceModel.setOracle(IDENTITY_ORACLE);
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionAppContext",
                    "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
            System.clearProperty("arm2.appContext");
        }
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    /** Picks whatever class the resolver listed as a classpath candidate. */
    private static final LlmOracle CANDIDATE_ORACLE = q -> {
        Matcher m = Pattern.compile("classpath[^:]*: \\[([^,\\]]+)").matcher(q.prompt());
        return new LlmResponse(m.find() ? m.group(1) : "", false, 0.0);
    };

    @Test
    void classpathCandidatesGroundResolution() {
        // arm2.classHint lists real classpath classes matching the id; the LLM then
        // picks a real class (grounding the proposal) instead of hallucinating a name.
        System.setProperty("arm2.classHint", "Gadget");
        LlmInferenceModel.setOracle(CANDIDATE_ORACLE);
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionAppContext",
                    "reflection-inference:llm");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
            System.clearProperty("arm2.classHint");
        }
        assertTrue(reachable.contains("Gadget.boot"),
                "arm② must list real classpath classes matching the id so the LLM grounds its "
                        + "proposal in an existing class → Gadget.boot reachable; got " + reachable);
    }

    @Test
    void identityContextEnablesResolution() {
        // Without the identity, the oracle cannot resolve → non-vacuity.
        assertFalse(reachableWithContext(null).contains("Gadget.boot"),
                "without app identity, the LLM has no basis → Gadget.boot must be unreachable");
        // With the identity fed into the prompt, the LLM proposes Gadget → resolved.
        assertTrue(reachableWithContext("this is the GADGET-APP benchmark").contains("Gadget.boot"),
                "arm② must feed the app identity into the prompt so the LLM resolves Gadget "
                        + "→ Gadget.<init> → boot() reachable");
    }
}
