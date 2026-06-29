package pta.eval;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link SoundnessChecker}.
 *
 * <p>Uses {@code BoxAlias} (from {@code src/test/resources/pta/eval})
 * as a small but non-trivial benchmark with multiple call-graph edges.
 *
 * <h2>Identity semantics</h2>
 * <p>{@code pascal.taie.ir.stmt.Invoke} and
 * {@code pascal.taie.language.classes.JMethod} do not override
 * {@code equals}/{@code hashCode}, so each call to {@code Main.main}
 * (which resets the Tai-e {@code World}) produces fresh object instances.
 * Edges collected from one run will therefore <em>never</em> be found in the
 * call graph of a subsequent run — even if both analyses are structurally
 * identical.  The cross-run tests exploit this to build honest
 * "candidate drops real edges" and "candidate has edges absent from sound"
 * scenarios without requiring mock objects or custom stubs.
 */
public class SoundnessCheckerTest {

    /**
     * Step 1a – recall == 1.0: When the ground-truth edges and the candidate
     * come from the <em>same</em> call graph object (same Java references),
     * every ground-truth edge is found in the candidate → recall must be 1.0.
     *
     * <p>This is non-tautological: the implementation must iterate the
     * ground-truth set and perform a hash-set lookup against the candidate's
     * edge collection; if either iteration or containment is broken the
     * assertion fails.
     */
    @Test
    void recallIsOneWhenAllGroundTruthEdgesAreInCandidate() {
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        Set<Edge<Invoke, JMethod>> groundTruth =
                result.getCallGraph().edges().collect(Collectors.toSet());

        assertFalse(groundTruth.isEmpty(),
                "BoxAlias CI call graph must contain at least one edge");

        SoundnessChecker checker = new SoundnessChecker();
        double recall = checker.recallVsGroundTruth(groundTruth, result);

        assertEquals(1.0, recall, 1e-9,
                "All ground-truth edges are in the candidate (same run) — recall must be 1.0");
    }

    /**
     * Step 1b – recall < 1.0: When the ground-truth edges were collected from
     * a <em>previous</em> World instance and the candidate comes from a fresh
     * run of the same analysis, Invoke/JMethod identity mismatch means no
     * ground-truth edge will be found in the candidate → recall must be 0.0.
     *
     * <p>This models the real use-case where the candidate (e.g. a context-
     * sensitive arm) drops call-graph edges that were present in the B0
     * ground-truth.
     */
    @Test
    void recallIsLessThanOneWhenCandidateDropsRealEdges() {
        // Run 1 – collect ground truth from this World instance.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult run1Result = World.get().getResult(PointerAnalysis.ID);
        Set<Edge<Invoke, JMethod>> groundTruth =
                run1Result.getCallGraph().edges().collect(Collectors.toSet());
        assertFalse(groundTruth.isEmpty(),
                "BoxAlias CI call graph must contain at least one edge (run 1)");

        // Run 2 – fresh World; all Invoke/JMethod objects are new instances.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult run2Result = World.get().getResult(PointerAnalysis.ID);

        // No edge from run 1 equals any edge from run 2 (identity inequality).
        SoundnessChecker checker = new SoundnessChecker();
        double recall = checker.recallVsGroundTruth(groundTruth, run2Result);

        assertTrue(recall < 1.0,
                "Ground-truth edges come from a different World instance; "
                + "no match possible — recall must be < 1.0 (got " + recall + ")");
    }

    /**
     * Step 1c – subsumes == true: When sound and candidate are the
     * <em>same</em> {@link PointerAnalysisResult} object (every edge in
     * the candidate is trivially in the sound call graph), subsumes must
     * return {@code true}.
     *
     * <p>The implementation must correctly iterate the candidate's call-graph
     * edges and verify each against the sound's edge set; a trivially wrong
     * implementation that always returns {@code false} will fail this test.
     */
    @Test
    void subsumesTrueWhenAllCandidateEdgesAreInSound() {
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        assertFalse(result.getCallGraph().edges().findAny().isEmpty(),
                "BoxAlias CI call graph must be non-empty for this test to be non-vacuous");

        SoundnessChecker checker = new SoundnessChecker();
        assertTrue(checker.subsumes(result, result),
                "A result must subsume itself — every edge in the candidate is in the sound CG");
    }

    /**
     * Step 1d – subsumes == false: When the candidate comes from a
     * <em>different</em> World instance than the sound, the candidate's edges
     * (new object references) are absent from the sound's call graph → at
     * least one candidate edge fails the containment check → subsumes must
     * return {@code false}.
     *
     * <p>This models the unsound-candidate scenario where an arm introduces
     * a call edge that was not present in the B0 sound over-approximation.
     */
    @Test
    void subsumesFalseWhenCandidateHasEdgeAbsentFromSound() {
        // Run 1 – collect "sound" from this World instance.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult soundResult = World.get().getResult(PointerAnalysis.ID);

        // Run 2 – fresh World; candidate edges are new object instances.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult candidateResult = World.get().getResult(PointerAnalysis.ID);

        assertFalse(candidateResult.getCallGraph().edges().findAny().isEmpty(),
                "BoxAlias CI call graph must be non-empty so at least one edge fails the check");

        SoundnessChecker checker = new SoundnessChecker();
        assertFalse(checker.subsumes(soundResult, candidateResult),
                "Candidate edges are from a different World — none match sound's edge set → subsumes false");
    }
}
