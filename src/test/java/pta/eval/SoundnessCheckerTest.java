package pta.eval;

import org.junit.jupiter.api.Test;
import pascal.taie.World;
import pascal.taie.analysis.Tests;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysis;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SoundnessChecker}.
 *
 * <p>Uses {@code BoxAlias} (from {@code src/test/resources/pta/eval})
 * as a small but non-trivial benchmark with multiple call-graph edges.
 *
 * <h2>Test strategy</h2>
 * <p>Edge equality in Tai-e uses object identity for {@code Invoke} and
 * {@code JMethod} (neither overrides {@code equals}/{@code hashCode}).
 * The value-key fix in {@link SoundnessChecker} converts edges to
 * {@link SoundnessChecker.EdgeKey} triples before comparison, making
 * cross-run and cross-World-instance comparisons semantically correct.
 *
 * <p>Tests {@link #recallIsLessThanOneWhenCandidateDropsRealEdges} and
 * {@link #subsumesFalseWhenCandidateHasEdgeAbsentFromSound} exercise the
 * comparison logic via the package-private
 * {@link SoundnessChecker#recallByKeys} and
 * {@link SoundnessChecker#subsumesByKeys} helpers with synthetic key sets
 * built from a single real analysis run — this is the only way to model
 * "genuine subset/superset" semantics without Mockito or additional benchmark
 * programs.  The public-API, cross-run regression guard
 * {@link #crossRunSameConfigSubsumesAndRecallOneAfterValueKeyFix} tests
 * the full end-to-end path and would fail under the old identity
 * implementation.
 */
public class SoundnessCheckerTest {

    /**
     * Step 1a – recall == 1.0: public API, same-run ground truth.
     *
     * <p>When the ground-truth edges and the candidate come from the same
     * call graph object (same Java references), every ground-truth edge is
     * found in the candidate → recall must be 1.0.
     *
     * <p>This exercises the public {@link SoundnessChecker#recallVsGroundTruth}
     * path and confirms that value-key conversion of real edges round-trips
     * correctly.
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
     * Step 1b – recall &lt; 1.0: genuine missing-edge scenario.
     *
     * <p>Collects the full EdgeKey set from a single CI run, then removes one
     * key to model a candidate that genuinely drops a real call-graph edge.
     * Tests via the package-private {@link SoundnessChecker#recallByKeys}
     * helper so the test controls the exact key sets without needing a second
     * analysis run or a {@code PointerAnalysisResult} mock.
     *
     * <p>Previously this test used two separate World runs with identical
     * configurations and relied on identity mismatch to produce recall &lt; 1.0
     * — that only worked because of the identity bug and did not model any real
     * semantic difference.
     */
    @Test
    void recallIsLessThanOneWhenCandidateDropsRealEdges() {
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        SoundnessChecker checker = new SoundnessChecker();
        Set<SoundnessChecker.EdgeKey> fullKeys = checker.toKeySet(result);

        assertFalse(fullKeys.isEmpty(),
                "BoxAlias CI must produce at least one edge");

        // Simulate a candidate that drops one real edge: remove one key.
        SoundnessChecker.EdgeKey dropped = fullKeys.iterator().next();
        Set<SoundnessChecker.EdgeKey> candidateKeys = new HashSet<>(fullKeys);
        candidateKeys.remove(dropped);

        // groundTruth = full N keys; candidateKeys = N-1 keys → recall = (N-1)/N < 1.0
        double recall = checker.recallByKeys(fullKeys, candidateKeys);

        assertTrue(recall < 1.0,
                "Candidate is missing one ground-truth edge — recall must be < 1.0, got " + recall);
        assertEquals((double) (fullKeys.size() - 1) / fullKeys.size(), recall, 1e-9,
                "Recall must equal (N-1)/N");
    }

    /**
     * Step 1c – subsumes == true: public API, same result.
     *
     * <p>When sound and candidate are the same {@link PointerAnalysisResult},
     * every candidate edge key is trivially present in the sound key set →
     * subsumes must return {@code true}.
     *
     * <p>The assertion is non-vacuous: BoxAlias CI produces a non-empty call
     * graph, so at least one membership check is performed.
     */
    @Test
    void subsumesTrueWhenAllCandidateEdgesAreInSound() {
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        assertFalse(result.getCallGraph().edges().findAny().isEmpty(),
                "BoxAlias CI call graph must be non-empty for this test to be non-vacuous");

        SoundnessChecker checker = new SoundnessChecker();
        assertTrue(checker.subsumes(result, result),
                "A result must subsume itself — every candidate edge key is in the sound key set");
    }

    /**
     * Step 1d – subsumes == false: genuine extra-edge-in-candidate scenario.
     *
     * <p>Collects the full EdgeKey set from a single CI run.  The sound key
     * set is constructed as (full set minus one key); the candidate key set is
     * the full set.  Because the candidate has a key absent from the sound,
     * {@link SoundnessChecker#subsumesByKeys} must return {@code false}.
     *
     * <p>Previously this test ran the same configuration twice (two World
     * resets) and exploited identity mismatch to produce a false result — the
     * old test passed only because of the identity bug, with no semantic
     * difference between sound and candidate.
     */
    @Test
    void subsumesFalseWhenCandidateHasEdgeAbsentFromSound() {
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult result = World.get().getResult(PointerAnalysis.ID);

        SoundnessChecker checker = new SoundnessChecker();
        Set<SoundnessChecker.EdgeKey> fullKeys = checker.toKeySet(result);

        assertFalse(fullKeys.isEmpty(),
                "BoxAlias CI must produce at least one edge");

        // Sound is missing one key; candidate has the full set.
        // The candidate therefore has an edge absent from the sound → subsumes = false.
        SoundnessChecker.EdgeKey missing = fullKeys.iterator().next();
        Set<SoundnessChecker.EdgeKey> soundKeys = new HashSet<>(fullKeys);
        soundKeys.remove(missing);

        assertFalse(checker.subsumesByKeys(soundKeys, fullKeys),
                "Candidate has a key absent from sound (we removed one from sound) — "
                + "subsumesByKeys must return false");
    }

    /**
     * Regression guard: cross-run, public API — proves the value-key fix works
     * end-to-end.
     *
     * <p>Runs B0 (CI) on BoxAlias TWICE in separate Tai-e {@code World}
     * instances. Because {@link Invoke} and {@link JMethod} do not override
     * {@code equals}/{@code hashCode}, the two runs produce identity-inequal
     * objects: under the <em>old</em> identity-based implementation,
     * {@link SoundnessChecker#subsumes} would unconditionally return
     * {@code false} and {@link SoundnessChecker#recallVsGroundTruth} would
     * return {@code 0.0} for any cross-run comparison.  Under the value-key
     * fix, same analysis on same program → same EdgeKey set → subsumes true
     * and recall 1.0.
     *
     * <p>This test is the definitive evidence that the identity bug is fixed:
     * it would fail under the old implementation and pass only after the
     * value-key change.
     */
    @Test
    void crossRunSameConfigSubsumesAndRecallOneAfterValueKeyFix() {
        // Run 1 — collect ground-truth edges and result.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult run1 = World.get().getResult(PointerAnalysis.ID);
        Set<Edge<Invoke, JMethod>> run1Edges =
                run1.getCallGraph().edges().collect(Collectors.toSet());

        assertFalse(run1Edges.isEmpty(),
                "BoxAlias CI must produce at least one edge (run 1)");

        // Run 2 — fresh World; all Invoke/JMethod objects are new instances.
        Tests.testPTA(false, "eval", "BoxAlias", "cs:ci");
        PointerAnalysisResult run2 = World.get().getResult(PointerAnalysis.ID);

        SoundnessChecker checker = new SoundnessChecker();

        // Value-key fix: same analysis → same EdgeKeys → subsumes true.
        // OLD identity impl: different World → fresh objects → subsumes false (BUG).
        assertTrue(checker.subsumes(run1, run2),
                "Same config run twice: value-key subsumes must be true "
                + "(would be false under identity bug)");

        // Value-key fix: same EdgeKeys → recall 1.0.
        // OLD identity impl: no identity match across worlds → recall 0.0 (BUG).
        assertEquals(1.0, checker.recallVsGroundTruth(run1Edges, run2), 1e-9,
                "Same config run twice: value-key recall must be 1.0 "
                + "(would be 0.0 under identity bug)");
    }
}
