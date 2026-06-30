package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.language.classes.JMethod;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link ReflectionDisposer} (arm② §11 step 3: the two-layer sound
 * disposer = base {@link TargetFilter} ∩ use-site type bound). The LLM's
 * proposals are clamped to types consistent with the post-dominating downcast,
 * so a wrong proposal is removed on precision grounds; because arm② is add-only,
 * the clamp can never drop a base-resolved edge (sound under correct-casts).
 */
public class ReflectionDisposerTest {

    private static PointerAnalysisResult analyze(String main) {
        Tests.testPTA(false, "reflection", main,
                "reflection-inference:string-constant", "only-app:true");
        return World.get().getResult(PointerAnalysis.ID);
    }

    private static FlaggedSite siteOf(List<FlaggedSite> sites, ReflectiveKind kind) {
        return sites.stream()
                .filter(s -> s.kind() == kind && s.container().getName().equals("main"))
                .findFirst().orElseThrow();
    }

    private static Set<String> classNames(Set<JMethod> ms) {
        return ms.stream().map(m -> m.getDeclaringClass().getName()).collect(Collectors.toSet());
    }

    /**
     * newInstance result is downcast to Animal. The LLM proposes Dog (a subtype)
     * and Cat (not a subtype). The disposer must admit Dog and clamp out Cat.
     */
    @Test
    void clampsNewInstanceProposalsToDowncastSubtypes() {
        PointerAnalysisResult r = analyze("ArmReflectionEvidence");
        FlaggedSite ni = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.NEW_INSTANCE);
        ReflectionEvidence ev = EvidenceCollector.collect(ni);
        Set<JMethod> admitted = ReflectionDisposer.admit(ni, List.of("Dog", "Cat"), ev,
                World.get().getClassHierarchy(), World.get().getTypeSystem());
        Set<String> classes = classNames(admitted);
        assertTrue(classes.contains("Dog"),
                "Dog <: Animal (downcast bound) must be admitted; got " + classes);
        assertFalse(classes.contains("Cat"),
                "Cat is not <: Animal — the downcast bound must clamp it out; got " + classes);
    }

    /**
     * When there is no downcast bound (LlmReflection: {@code Object o = c.newInstance()}),
     * the disposer falls back to the base TargetFilter and admits the type-valid
     * proposal (sound, just less precise).
     */
    @Test
    void noDowncastFallsBackToBaseFilter() {
        PointerAnalysisResult r = analyze("LlmReflection");
        FlaggedSite ni = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.NEW_INSTANCE);
        ReflectionEvidence ev = EvidenceCollector.collect(ni);
        Set<JMethod> admitted = ReflectionDisposer.admit(ni, List.of("Service"), ev,
                World.get().getClassHierarchy(), World.get().getTypeSystem());
        assertTrue(classNames(admitted).contains("Service"),
                "with no downcast bound the type-valid proposal Service must be admitted; got "
                        + classNames(admitted));
    }
}
