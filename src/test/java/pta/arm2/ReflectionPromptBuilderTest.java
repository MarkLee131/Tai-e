package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link ReflectionPromptBuilder} (arm② §11 step 2: the rich
 * use-site-evidence prompt — the replacement for the old siteId-only prompt).
 */
public class ReflectionPromptBuilderTest {

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

    /** The newInstance prompt must carry the downcast bound and ask for class names. */
    @Test
    void newInstancePromptCarriesDowncastAndAsksForClasses() {
        PointerAnalysisResult r = analyze("ArmReflectionEvidence");
        FlaggedSite ni = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.NEW_INSTANCE);
        String prompt = ReflectionPromptBuilder.build(ni, EvidenceCollector.collect(ni, r));
        assertTrue(prompt.contains("Animal"),
                "prompt must carry the downcast type bound (Animal); got:\n" + prompt);
        assertTrue(prompt.toLowerCase().contains("class"),
                "prompt must ask for class names; got:\n" + prompt);
    }

    /** The invoke prompt must carry the receiver-type bound. */
    @Test
    void invokePromptCarriesReceiverType() {
        PointerAnalysisResult r = analyze("ArmReflectionEvidence");
        FlaggedSite inv = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.INVOKE);
        String prompt = ReflectionPromptBuilder.build(inv, EvidenceCollector.collect(inv, r));
        assertTrue(prompt.contains("Dog"),
                "invoke prompt must carry the receiver-type bound (Dog); got:\n" + prompt);
    }
}
