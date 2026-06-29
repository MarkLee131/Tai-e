package pta.arm3.datalog;

import pta.arm3.AliasFact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal Java-embedded, stratified-closure consistency check over a base
 * alias relation. There is NO dependency on any external solver
 * (Souffle/clingo); the closure is a tiny union-find Datalog program.
 *
 * <h3>Rule set (documented stratification)</h3>
 * The base relation is {@code alias(X, Y)} for every base {@link AliasFact}
 * with {@code mayAlias == true}. We compute its reflexive-symmetric-transitive
 * closure {@code aliasClosure}:
 * <pre>
 *   // stratum 0: seed
 *   aliasClosure(X, X).                       // reflexivity
 *   aliasClosure(X, Y) :- alias(X, Y).        // base edges (symmetric)
 *   aliasClosure(Y, X) :- alias(X, Y).
 *   // stratum 1: transitive closure (computed to fixpoint)
 *   aliasClosure(X, Z) :- aliasClosure(X, Y), aliasClosure(Y, Z).
 *   // stratum 2: contradiction (stratified negation over the closed relation)
 *   contradiction(X, Y) :- proposedNeverAlias(X, Y), aliasClosure(X, Y).
 * </pre>
 * A proposed {@code never-alias(X, Y)} fact (an {@link AliasFact} with
 * {@code mayAlias == false}) is REJECTED iff {@code contradiction(X, Y)} holds,
 * i.e. X and Y are in the same alias equivalence class of the base least model.
 * Because closure is monotone and the negation in stratum 2 is applied only
 * after the closure reaches fixpoint, the program is stratified and has a
 * unique least model. Surviving (non-contradicted) proposals are returned.
 *
 * <p>Proposed facts with {@code mayAlias == true} impose no restriction and are
 * always retained as survivors.
 */
public class ConsistencyEngine {

    private final List<AliasFact> rejected = new ArrayList<>();

    /**
     * Arbitrates {@code llmProposed} against {@code base}.
     *
     * @param base        sound base alias facts (mayAlias==true edges)
     * @param llmProposed defeasible LLM facts (never-alias = mayAlias==false)
     * @return the proposals that do not contradict the base closure
     */
    public List<AliasFact> accept(List<AliasFact> base, List<AliasFact> llmProposed) {
        rejected.clear();
        UnionFind uf = new UnionFind();
        for (AliasFact f : base) {
            if (f.mayAlias()) {
                uf.union(f.groupA(), f.groupB());
            }
        }
        List<AliasFact> survivors = new ArrayList<>();
        for (AliasFact f : llmProposed) {
            if (!f.mayAlias() && uf.connected(f.groupA(), f.groupB())) {
                // never-alias contradicts an alias in the base closure
                rejected.add(f);
            } else {
                survivors.add(f);
            }
        }
        return survivors;
    }

    public List<AliasFact> getRejected() {
        return rejected;
    }

    /**
     * Tiny union-find computing the reflexive-symmetric-transitive closure of
     * the base alias relation. {@code connected(x, x)} is true by reflexivity
     * even for groups never seen in the base.
     */
    private static final class UnionFind {

        private final Map<String, String> parent = new HashMap<>();

        private String find(String x) {
            parent.putIfAbsent(x, x);
            String root = x;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            // path compression
            String cur = x;
            while (!parent.get(cur).equals(root)) {
                String next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(String a, String b) {
            parent.put(find(a), find(b));
        }

        boolean connected(String a, String b) {
            return find(a).equals(find(b));
        }
    }
}
