package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pta.llm.LlmResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOLAR-N3 self-flagging on the production model: a residual reflective site the
 * LLM cannot resolve to a loaded target is recorded in
 * {@link LlmInferenceModel#gapReport()} instead of being silently dropped.
 */
public class LlmInferenceGapReportTest {

    @Test
    void unresolvableResidualSiteIsFlagged() {
        // Garbage oracle: proposes an unloadable class → nothing injected.
        LlmInferenceModel.setOracle(q -> new LlmResponse("no.such.Class$$$", false, 0.0));
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionInvoke", "reflection-inference:llm");
            assertFalse(LlmInferenceModel.gapReport().isEmpty(),
                    "an unresolvable residual reflective site must be flagged in the gap report");
        } finally {
            LlmInferenceModel.clearOracle();
        }
    }

    @Test
    void resolvedSiteIsNotFlagged() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-class".equals(q.kind()) ? "GreeterImpl" : "greet", false, 0.0));
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionInvoke", "reflection-inference:llm");
            assertTrue(LlmInferenceModel.gapReport().stream()
                            .noneMatch(s -> s.contains("forName") || s.contains("@6")),
                    "a resolved forName site must not be flagged; got " + LlmInferenceModel.gapReport());
        } finally {
            LlmInferenceModel.clearOracle();
        }
    }
}
