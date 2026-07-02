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
 * H2: arm②'s always-on add-ons (SelfInferenceModel, ServiceLoaderModel) must attach only
 * for {@code reflection-inference:llm} (or explicit {@code -Darm2.addons}) — the shipped
 * baselines ({@code string-constant}/{@code solar}/{@code null}) must keep vanilla Tai-e
 * semantics so published-baseline comparisons and reproductions are uncontaminated.
 */
public class PureBaselineTest {

    private static Set<String> reachable(String main, String opts) {
        Tests.testPTA(false, "reflection", main, opts);
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        return r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
    }

    @Test
    void stringConstantBaselineIsVanilla() {
        // The forwarder idiom resolves ONLY via SelfInferenceModel's getName/getProperty
        // links; under the as-shipped string-constant baseline it must stay unresolved.
        Set<String> reach = reachable("ArmReflectionForwarder",
                "reflection-inference:string-constant");
        assertFalse(reach.contains("FwdTarget.init"),
                "H2: string-constant must be VANILLA Tai-e (no arm² add-ons); got FwdTarget "
                        + "resolved — add-ons leaked into the baseline");
    }

    @Test
    void llmModeCarriesTheAddons() {
        LlmInferenceModel.setOracle(q -> new LlmResponse("", false, 0.0)); // inert oracle
        Set<String> reach;
        try {
            reach = reachable("ArmReflectionForwarder", "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(reach.contains("FwdTarget.init"),
                "llm mode must carry the add-ons: the forwarder chain resolves without any "
                        + "LLM answer (pure self-inference); got " + reach);
    }
}
