package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.analysis.Tests;
import pta.llm.MockOracle;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for arm②'s three-state soundness-aware outcome (SOLAR N3): a residual
 * site the LLM+disposer cannot resolve is not silently dropped — it is recorded
 * in {@link LlmReflectionModel#gapReport()} so the analysis knows what it could
 * not resolve (the LLM-as-annotator self-flagging contribution).
 */
public class ReflectionGapReportTest {

    private static final String PLUGIN = "plugins:[pta.arm2.LlmReflectionModel]";

    /**
     * A garbage oracle proposes an unloadable class → the disposer admits
     * nothing → the residual newInstance site must be flagged in the gap report.
     */
    @Test
    void unresolvedSiteIsFlaggedInGapReport() {
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "no.such.Class$$$"));
        try {
            Tests.testPTA(false, "reflection", "LlmReflection",
                    "reflection-inference:string-constant", PLUGIN);
            List<String> gaps = LlmReflectionModel.gapReport();
            assertFalse(gaps.isEmpty(),
                    "an unresolvable residual site must be recorded in the gap report");
            assertTrue(gaps.stream().anyMatch(s -> s.contains("LlmReflection")),
                    "gap report should reference the unresolved site in LlmReflection; got " + gaps);
        } finally {
            LlmReflectionModel.clearOracle();
        }
    }

    /**
     * A correct oracle resolves the site → it must NOT appear in the gap report.
     */
    @Test
    void resolvedSiteIsNotInGapReport() {
        LlmReflectionModel.setOracle(new MockOracle(Map.of(), "Service"));
        try {
            Tests.testPTA(false, "reflection", "LlmReflection",
                    "reflection-inference:string-constant", PLUGIN);
            assertTrue(LlmReflectionModel.gapReport().isEmpty(),
                    "a resolved site must not be flagged as a gap; got "
                            + LlmReflectionModel.gapReport());
        } finally {
            LlmReflectionModel.clearOracle();
        }
    }
}
