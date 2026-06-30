package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link ReflectionSiteLocator} (arm② §11 step 1: self-flagging
 * localization of residual reflective sites the base analysis could not resolve).
 */
public class ReflectionSiteLocatorTest {

    /** CI pre-analysis with base string-constant reflection inference. */
    private static PointerAnalysisResult analyze(String main) {
        Tests.testPTA(false, "reflection", main,
                "reflection-inference:string-constant", "only-app:true");
        return World.get().getResult(PointerAnalysis.ID);
    }

    /**
     * In {@code LlmReflection}, the class name into {@code Class.forName} is a
     * non-constant string, so the base analysis leaves {@code c.newInstance()}
     * with no callee. The locator must flag exactly that site as residual.
     */
    @Test
    void flagsUnresolvedNewInstanceSite() {
        PointerAnalysisResult r = analyze("LlmReflection");
        List<FlaggedSite> sites = ReflectionSiteLocator.locate(r);
        assertTrue(sites.stream().anyMatch(s ->
                        s.kind() == ReflectiveKind.NEW_INSTANCE
                                && s.container().getName().equals("main")),
                "must flag the unresolved Class.newInstance() in LlmReflection.main; got " + sites);
    }
}
