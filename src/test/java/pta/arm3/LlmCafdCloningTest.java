package pta.arm3;

import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.exp.Var;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arm ③ redesign — "neuro-symbolic CAFD done right": SOUND per-callsite heap
 * cloning.
 *
 * <h2>Thesis under test ("LLM proposes, sound logic disposes")</h2>
 * <p>The LLM is a PROPOSER only: it nominates methods it believes are
 * fresh-allocation wrappers. B3's structural {@link pta.baseline.cafd.WrapperDetector}
 * (run on a context-insensitive pre-analysis) is the sound DISPOSER: only
 * methods it independently confirms are cloned. Cloning refines the heap
 * abstraction (more abstract objects) — it can never drop a real edge/alias.
 *
 * <p>Wired as the {@code advanced:llm-cafd} branch in {@link PointerAnalysis},
 * mirroring B3's {@code advanced:cafd}.
 *
 * <p>Both tests run on {@code WrapperProgram} (the B3 benchmark), which has:
 * <ul>
 *   <li>{@code make()} — a genuine fresh-allocation wrapper.</li>
 *   <li>{@code mutate()} — NOT a wrapper (writes its result to a static field).</li>
 * </ul>
 */
public class LlmCafdCloningTest {

    private static final String DIR = "src/test/resources/pta/cafd";
    private static final String ARM3_DIR = "src/test/resources/pta/arm3";
    private static final String MAIN = "WrapperProgram";

    /** Sum of points-to set sizes across all reachable variables. */
    private static long totalPointsTo(PointerAnalysisResult pta) {
        long sum = 0;
        for (Var v : pta.getVars()) {
            sum += pta.getPointsToSet(v).size();
        }
        return sum;
    }

    private static PointerAnalysisResult runCi() {
        Main.main("-cp", DIR, "-m", MAIN, "-a",
                "pta=cs:ci;only-app:true;implicit-entries:false");
        return World.get().getResult(PointerAnalysis.ID);
    }

    private static PointerAnalysisResult runLlmCafd(String mockFile) {
        Main.main("-cp", DIR, "-m", MAIN, "-a",
                "pta=advanced:llm-cafd;only-app:true;implicit-entries:false"
                        + ";llm-mock-file:" + mockFile);
        return World.get().getResult(PointerAnalysis.ID);
    }

    /**
     * BENEFIT (C2): the LLM proposes the GENUINE wrapper {@code make()}; the
     * detector confirms it, so it is cloned per call site. Same direction as
     * B3's own test: #abstract objects increases and total points-to size
     * decreases versus the context-insensitive baseline.
     */
    @Test
    void genuineWrapperProposalIsClonedAndImprovesPrecision() {
        PointerAnalysisResult ci = runCi();
        int ciObjects = ci.getObjects().size();
        long ciPts = totalPointsTo(ci);

        PointerAnalysisResult llm = runLlmCafd(ARM3_DIR + "/propose-make.txt");
        int llmObjects = llm.getObjects().size();
        long llmPts = totalPointsTo(llm);

        assertTrue(llmObjects > ciObjects, String.format(
                "arm③ cloning (#obj=%d) must produce more abstract objects than CI "
                        + "(#obj=%d): make() is cloned per call site", llmObjects, ciObjects));
        assertTrue(llmPts < ciPts, String.format(
                "arm③ cloning (pts-sum=%d) must be strictly less than CI (pts-sum=%d): "
                        + "per-callsite objects do not merge across call sites",
                llmPts, ciPts));
    }

    /**
     * SOUNDNESS REGRESSION (C1): the LLM proposes {@code mutate()} — a NON-wrapper
     * (it writes its freshly-allocated object into the static field {@code leaked},
     * an escaping side effect). The sound disposer (WrapperDetector) REJECTS it,
     * so it is NOT cloned and the analysis result is IDENTICAL to the no-arm③ CI
     * baseline. In particular the real flow into {@code leaked} is preserved.
     *
     * <p>This test MUST fail if the disposer were bypassed: cloning {@code mutate()}
     * suppresses its body — including the {@code leaked = d} static store — which
     * drops a real points-to flow and shrinks the total points-to sum below CI.
     */
    @Test
    void nonWrapperProposalIsRejectedAndPreservesBaseline() {
        PointerAnalysisResult ci = runCi();
        int ciObjects = ci.getObjects().size();
        long ciPts = totalPointsTo(ci);

        PointerAnalysisResult llm = runLlmCafd(ARM3_DIR + "/propose-mutate.txt");
        int llmObjects = llm.getObjects().size();
        long llmPts = totalPointsTo(llm);

        assertEquals(ciObjects, llmObjects,
                "SOUNDNESS: a non-wrapper (mutate) proposal must be rejected by the "
                        + "detector — #abstract objects must equal the CI baseline (no cloning)");
        assertEquals(ciPts, llmPts,
                "SOUNDNESS: rejecting mutate must leave the result identical to CI. "
                        + "If the disposer were bypassed, suppressing mutate's body would "
                        + "drop the real `leaked = d` static store and shrink the pts sum.");
    }
}
