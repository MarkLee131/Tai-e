package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.plugin.reflection.LlmInferenceModel;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.Reflections;
import pta.llm.LlmResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1: {@code Reflections.getFields} must implement {@code Class.getField}'s documented
 * lookup — this class, then superinterfaces (recursively), then superclass — so interface
 * constants are found. Verified both at the lookup level and via the SOLAR-N3 ledger.
 */
public class ReflectionIfaceFieldTest {

    @Test
    void getFieldsSearchesSuperinterfaces() {
        LlmInferenceModel.setOracle(q -> new LlmResponse(
                "llm-field".equals(q.kind()) ? "HANDLER" : "", false, 0.0));
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionIfaceField",
                    "reflection-inference:llm");
        } finally {
            LlmInferenceModel.clearOracle();
        }
        // Lookup-level: the interface constant must be found on the implementing class.
        JClass impl = World.get().getClassHierarchy().getClass("Impl");
        assertTrue(Reflections.getFields(impl, "HANDLER").findAny().isPresent(),
                "getFields(Impl, HANDLER) must find the constant declared on interface Cfg");
        // Ledger-level: the site resolved a real field, so it must not be flagged.
        List<String> gaps = LlmInferenceModel.gapReport();
        assertFalse(gaps.stream().anyMatch(g -> g.contains("ArmReflectionIfaceField")),
                "the getField site resolved HANDLER — must not be in the gap report: " + gaps);
    }
}
