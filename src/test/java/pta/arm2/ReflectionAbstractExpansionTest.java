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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a reflective forName resolves to an ABSTRACT class / interface, the real
 * newInstance object is a concrete subtype. arm② must expand to the instantiable
 * subclasses (the antlr.Tool.doEverything → antlr.CodeGenerator → {Cpp,CSharp,Java}
 * CodeGenerator residual). Deterministic use of the class hierarchy.
 */
public class ReflectionAbstractExpansionTest {

    private static final LlmOracle ORACLE = q -> new LlmResponse(
            "llm-class".equals(q.kind()) ? "AbsBase" : "", false, 0.0);

    @Test
    void expandsAbstractTargetToConcreteSubclasses() {
        LlmInferenceModel.setOracle(ORACLE);
        Set<String> reachable;
        try {
            Tests.testPTA(false, "reflection", "ArmReflectionAbstract",
                    "reflection-inference:llm");
            PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
            reachable = r.getCallGraph().reachableMethods()
                    .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                    .collect(Collectors.toSet());
        } finally {
            LlmInferenceModel.clearOracle();
        }
        assertTrue(reachable.contains("AbsImplA.pingA"),
                "abstract AbsBase must expand to concrete AbsImplA → newInstance → init → pingA; got "
                        + reachable);
        assertTrue(reachable.contains("AbsImplB.pingB"),
                "abstract AbsBase must expand to concrete AbsImplB → pingB reachable");
    }
}
