package pta.eval;

import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure read-only comparator for {@link PointerAnalysisResult} objects.
 *
 * <p>This class is side-effect-free: it only reads call-graph edges from the
 * supplied results and performs set-membership checks.  No LLM calls, no
 * analysis re-runs, no mutation of world state.
 *
 * <h2>Subsumption</h2>
 * <p>{@link #subsumes} checks whether every call-graph edge in the
 * <em>candidate</em> result is also present in the <em>sound</em> result (B0,
 * the context-insensitive over-approximation).  A candidate that introduces a
 * call edge absent from B0 is potentially unsound; {@link #subsumes} will
 * return {@code false} in that case.
 *
 * <p>Note on arm ②: the LLM reflection arm is designed to <em>add</em>
 * edges (resolved reflection targets) to the call graph.  For that arm,
 * {@link #subsumes} will typically return {@code false} — the relevant
 * soundness metric for arm ② is {@link #recallVsGroundTruth} against a
 * reflection ground-truth edge set, not subsumption by B0.
 *
 * <h2>Recall vs ground truth</h2>
 * <p>{@link #recallVsGroundTruth} computes the fraction of a supplied
 * {@code groundTruth} edge set that is covered by the candidate's call graph.
 * Recall == 1.0 means the candidate retains every ground-truth edge; recall
 * &lt; 1.0 means some ground-truth edges are absent from the candidate.
 *
 * <h2>Edge equality</h2>
 * <p>Edge equality is defined by
 * {@link pascal.taie.analysis.graph.callgraph.Edge#equals}, which compares
 * (kind, callSite, callee) using the respective {@code .equals()} of each
 * component.  Because {@link Invoke} and {@link JMethod} do not override
 * {@code equals}/{@code hashCode}, equality reduces to object identity.
 * Callers must ensure that both results originate from the <em>same</em> Tai-e
 * {@code World} instance for meaningful cross-result comparisons.
 */
public class SoundnessChecker {

    /**
     * Returns {@code true} iff every call-graph edge in {@code candidate} is
     * also present in {@code sound}'s call graph (candidate ⊆ sound).
     *
     * <p>The check iterates {@code candidate.getCallGraph().edges()} and
     * verifies each edge against a {@link Set} built from
     * {@code sound.getCallGraph().edges()}.  The overall complexity is
     * O(|sound| + |candidate|).
     *
     * @param sound     the sound over-approximation result (e.g. B0 / CI)
     * @param candidate the analysis result to check
     * @return {@code true} if candidate call-graph ⊆ sound call-graph
     */
    public boolean subsumes(PointerAnalysisResult sound, PointerAnalysisResult candidate) {
        Set<Edge<Invoke, JMethod>> soundEdges =
                sound.getCallGraph().edges().collect(Collectors.toSet());
        return candidate.getCallGraph().edges().allMatch(soundEdges::contains);
    }

    /**
     * Returns the fraction of {@code groundTruth} edges that are present in
     * {@code candidate}'s call graph.
     *
     * <p>Formally:
     * <pre>
     *   recall = |groundTruth ∩ candidateEdges| / |groundTruth|
     * </pre>
     *
     * <p>Returns {@code 1.0} when {@code groundTruth} is empty (vacuously all
     * ground-truth edges are covered).
     *
     * @param groundTruth the reference edge set (e.g. manually annotated edges
     *                    or edges from a sound B0 run)
     * @param candidate   the analysis result to evaluate
     * @return recall in [0.0, 1.0]
     */
    public double recallVsGroundTruth(Set<Edge<Invoke, JMethod>> groundTruth,
                                      PointerAnalysisResult candidate) {
        if (groundTruth.isEmpty()) {
            return 1.0;
        }
        Set<Edge<Invoke, JMethod>> candidateEdges =
                candidate.getCallGraph().edges().collect(Collectors.toSet());
        long covered = groundTruth.stream().filter(candidateEdges::contains).count();
        return (double) covered / groundTruth.size();
    }
}
