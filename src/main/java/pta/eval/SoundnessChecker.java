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
 * <p>Edges are compared by value key ({@link EdgeKey}), encoding
 * (container method signature, call-site index, callee method signature).
 * This makes cross-run comparisons correct even when two separate
 * {@code Main.main} invocations produce fresh {@code Invoke}/{@code JMethod}
 * instances that are identity-inequal.
 */
public class SoundnessChecker {

    /**
     * Value key for a call-graph edge, independent of object identity.
     *
     * <p>{@link Invoke} and {@link JMethod} do not override
     * {@code equals}/{@code hashCode}, so {@code Edge.equals} reduces to
     * object identity.  This record captures the three string/int fields that
     * uniquely identify an edge across Tai-e {@code World} resets.
     *
     * <p>Package-private so that {@code pta.eval} test classes can construct
     * synthetic key sets to verify subset/superset semantics directly.
     *
     * @param containerSig  signature of the method containing the call site
     * @param callSiteIndex index of the {@link Invoke} statement in its method IR
     * @param calleeSig     signature of the callee method
     */
    record EdgeKey(String containerSig, int callSiteIndex, String calleeSig) {}

    /**
     * Converts a single call-graph edge to its {@link EdgeKey}.
     */
    private static EdgeKey toKey(Edge<Invoke, JMethod> e) {
        return new EdgeKey(
                e.getCallSite().getContainer().getSignature(),
                e.getCallSite().getIndex(),
                e.getCallee().getSignature());
    }

    /**
     * Collects all call-graph edges from {@code result} as a value-key set.
     * Package-private for use by {@code pta.eval} tests.
     *
     * @param result a pointer-analysis result
     * @return set of {@link EdgeKey} values for every edge in its call graph
     */
    Set<EdgeKey> toKeySet(PointerAnalysisResult result) {
        return result.getCallGraph().edges()
                .map(SoundnessChecker::toKey)
                .collect(Collectors.toSet());
    }

    /**
     * Core subsumption check over pre-computed key sets.
     * Package-private so tests can verify subset/superset semantics by
     * constructing synthetic key sets from a single analysis run.
     *
     * @param soundKeys     key set of the sound over-approximation
     * @param candidateKeys key set of the candidate result
     * @return {@code true} iff every key in {@code candidateKeys} is present
     *         in {@code soundKeys}
     */
    boolean subsumesByKeys(Set<EdgeKey> soundKeys, Set<EdgeKey> candidateKeys) {
        return candidateKeys.stream().allMatch(soundKeys::contains);
    }

    /**
     * Core recall computation over pre-computed key sets.
     * Package-private so tests can verify recall arithmetic by constructing
     * synthetic key sets from a single analysis run.
     *
     * @param groundTruthKeys key set of the ground-truth edges
     * @param candidateKeys   key set of the candidate's edges
     * @return fraction of ground-truth keys present in candidate keys,
     *         or {@code 1.0} when {@code groundTruthKeys} is empty
     */
    double recallByKeys(Set<EdgeKey> groundTruthKeys, Set<EdgeKey> candidateKeys) {
        if (groundTruthKeys.isEmpty()) {
            return 1.0;
        }
        long covered = groundTruthKeys.stream().filter(candidateKeys::contains).count();
        return (double) covered / groundTruthKeys.size();
    }

    /**
     * Returns {@code true} iff every call-graph edge in {@code candidate} is
     * also present in {@code sound}'s call graph (candidate ⊆ sound).
     *
     * <p>Edges are compared by {@link EdgeKey} value (container signature,
     * call-site index, callee signature), not by object identity.  This means
     * the comparison works correctly even when {@code sound} and
     * {@code candidate} come from separate {@code Main.main} invocations
     * (separate Tai-e {@code World} instances).
     *
     * @param sound     the sound over-approximation result (e.g. B0 / CI)
     * @param candidate the analysis result to check
     * @return {@code true} if candidate call-graph ⊆ sound call-graph
     */
    public boolean subsumes(PointerAnalysisResult sound, PointerAnalysisResult candidate) {
        return subsumesByKeys(toKeySet(sound), toKeySet(candidate));
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
     * <p>Each ground-truth edge is converted to an {@link EdgeKey} before
     * comparison, so cross-run usage (ground-truth edges from one World,
     * candidate from another) is correct.
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
        Set<EdgeKey> groundTruthKeys = groundTruth.stream()
                .map(SoundnessChecker::toKey)
                .collect(Collectors.toSet());
        return recallByKeys(groundTruthKeys, toKeySet(candidate));
    }
}
