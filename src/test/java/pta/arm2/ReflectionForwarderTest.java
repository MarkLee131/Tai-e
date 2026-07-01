package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-inferencing gap: {@code Class.forName(System.getProperty(key,
 * DefaultClass.class.getName()))} — the idiom lucene's SegmentReader/FSDirectory use,
 * which dominates the DaCapo reflection-recall gap. Resolving it requires modeling
 * {@code Class.getName()} → name-constant and {@code System.getProperty(k, default)}
 * → default. No LLM involved (deterministic self-inference).
 */
public class ReflectionForwarderTest {

    @Test
    void resolvesGetPropertyDefaultClassNameIdiom() {
        Tests.testPTA(false, "reflection", "ArmReflectionForwarder",
                "reflection-inference:string-constant");
        PointerAnalysisResult r = World.get().getResult(PointerAnalysis.ID);
        Set<String> reachable = r.getCallGraph().reachableMethods()
                .map(m -> m.getDeclaringClass().getName() + "." + m.getName())
                .collect(Collectors.toSet());
        assertTrue(reachable.contains("FwdTarget.init"),
                "self-inference must resolve forName(getProperty(k, FwdTarget.class.getName())) "
                        + "so IMPL.newInstance() → FwdTarget.<init> → init() is reachable; got "
                        + reachable);
    }
}
