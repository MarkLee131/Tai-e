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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1: when the abstract→concrete subtype expansion exceeds the cap, the site must be
 * FLAGGED as under-approximated (residual, SOLAR-N3), not silently truncated — aligning
 * with the formal model's "κ bounds precision effort, not soundness: exceeding it widens
 * (here, flags), never truncates." OverBase has 3 concrete subtypes; cap lowered to 2.
 */
public class ReflectionSubtypeOverflowTest {

    private static final LlmOracle BASE_ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "OverBase" : "", false, 0.0);

    @Test
    void subtypeOverflowIsFlaggedNotSilentlyTruncated() {
        System.setProperty("arm2.maxSubtypes", "2");
        LlmInferenceModel.setOracle(BASE_ORACLE);
        List<String> gaps;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionSubtypeOverflow",
                    "reflection-inference:llm");
            gaps = LlmInferenceModel.gapReport();
        } finally {
            LlmInferenceModel.clearOracle();
            System.clearProperty("arm2.maxSubtypes");
        }
        assertTrue(gaps.stream().anyMatch(g -> g.contains("ArmReflectionSubtypeOverflow")),
                "subtype-expansion overflow must flag the forName site as residual, not "
                        + "silently truncate; gapReport=" + gaps);
    }
}
