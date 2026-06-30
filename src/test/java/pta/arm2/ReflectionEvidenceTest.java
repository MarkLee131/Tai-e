package pta.arm2;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link EvidenceCollector} (arm② §11 step 2: rich use-site
 * evidence — the Elf "self-inferencing" signals — for a flagged reflective site).
 */
public class ReflectionEvidenceTest {

    private static PointerAnalysisResult analyze(String main) {
        Tests.testPTA(false, "reflection", main,
                "reflection-inference:string-constant", "only-app:true");
        return World.get().getResult(PointerAnalysis.ID);
    }

    private static FlaggedSite siteOf(List<FlaggedSite> sites, ReflectiveKind kind) {
        return sites.stream()
                .filter(s -> s.kind() == kind && s.container().getName().equals("main"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " site flagged in main; got " + sites));
    }

    /** The post-dominating downcast on a newInstance() result is the key Elf signal. */
    @Test
    void collectsDowncastTypeForNewInstance() {
        PointerAnalysisResult r = analyze("ArmReflectionEvidence");
        FlaggedSite ni = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.NEW_INSTANCE);
        ReflectionEvidence ev = EvidenceCollector.collect(ni);
        assertNotNull(ev.downcastType(), "newInstance result is downcast to (Animal)");
        assertTrue(ev.downcastType().contains("Animal"),
                "downcast on newInstance result should be Animal; got " + ev.downcastType());
    }

    /**
     * For Method.invoke, the receiver argument's type bounds the target class.
     * Tai-e narrows {@code Animal recv = new Dog()} to its allocated type
     * {@code Dog} (a tighter, still-sound bound), so the collected receiver type
     * is {@code Dog} — exactly the Elf "receiver type" signal we want.
     */
    @Test
    void collectsReceiverTypeForInvoke() {
        PointerAnalysisResult r = analyze("ArmReflectionEvidence");
        FlaggedSite inv = siteOf(ReflectionSiteLocator.locate(r), ReflectiveKind.INVOKE);
        ReflectionEvidence ev = EvidenceCollector.collect(inv);
        assertNotNull(ev.receiverType(), "invoke receiver arg has a static type");
        assertTrue(ev.receiverType().contains("Dog"),
                "invoke receiver type should be the receiver's class (Dog); got " + ev.receiverType());
    }
}
