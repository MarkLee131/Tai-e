package pta.baseline.cafd;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link WrapperDetector}.
 *
 * WrapperProgram has two relevant application methods:
 * - make()   — pure allocator wrapper (no side effects) → must be in the detected set
 * - mutate() — has a static-field write (side effect)  → must NOT be in the detected set
 *
 * The test runs a CI pre-analysis to obtain a {@link PointerAnalysisResult}, then
 * calls {@link WrapperDetector#detect} and checks the returned set.
 */
public class WrapperDetectorTest {

    private static final String DIR = "cafd";
    private static final String MAIN = "WrapperProgram";

    @Test
    void detectsMakeButNotMutate() {
        // Run CI pre-analysis to obtain a PointerAnalysisResult.
        Tests.testPTA(false, DIR, MAIN, "cs:ci");
        PointerAnalysisResult pre = World.get().getResult(PointerAnalysis.ID);
        ClassHierarchy h = World.get().getClassHierarchy();

        Set<JMethod> wrappers = WrapperDetector.detect(pre, h);

        JMethod makeMethod = pre.getCallGraph().reachableMethods()
                .filter(m -> m.getDeclaringClass().isApplication())
                .filter(m -> m.getName().equals("make"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("make() not reachable in CI pre-analysis"));

        JMethod mutateMethod = pre.getCallGraph().reachableMethods()
                .filter(m -> m.getDeclaringClass().isApplication())
                .filter(m -> m.getName().equals("mutate"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mutate() not reachable in CI pre-analysis"));

        assertTrue(wrappers.contains(makeMethod),
                "make() should be detected as an allocator wrapper");
        assertFalse(wrappers.contains(mutateMethod),
                "mutate() must NOT be detected — it writes to a static field");
    }
}
