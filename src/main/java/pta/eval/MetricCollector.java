package pta.eval;

import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts precision/cost metrics from a {@link PointerAnalysisResult} into a
 * {@link Metrics} record that serialises to a single CSV row.
 *
 * <p>Counting logic mirrors the bundled Tai-e client analyses:
 * <ul>
 *   <li>{@code mayFailCasts}  — logic from
 *       {@code pascal.taie.analysis.pta.client.MayFailCast}</li>
 *   <li>{@code polyCallSites} — logic from
 *       {@code pascal.taie.analysis.pta.client.PolymorphicCallSite}</li>
 *   <li>{@code aliasPairs}    — logic from
 *       {@code pascal.taie.analysis.pta.client.MayAliasPair}
 *       (computed over all reachable vars, not app-only)</li>
 * </ul>
 */
public class MetricCollector {

    // -----------------------------------------------------------------------
    // Public record
    // -----------------------------------------------------------------------

    /**
     * Immutable snapshot of analysis metrics.
     *
     * <p><b>Column order is intentional — do not reorder</b> (consumers rely on
     * position).
     *
     * @param mayFailCasts     number of cast sites that may fail at run time
     * @param avgPtsSize       mean points-to set size over vars with non-empty pts
     * @param polyCallSites    number of virtual/interface call sites with > 1 callee
     * @param reachableMethods number of methods in the call graph
     * @param aliasPairs       number of variable pairs that may alias
     * @param objects          total number of abstract objects allocated
     */
    public record Metrics(
            long mayFailCasts,
            double avgPtsSize,
            long polyCallSites,
            long reachableMethods,
            long aliasPairs,
            long objects) {

        /**
         * Stable CSV header — always one row, column names match record field order.
         * Prepend {@code toCsvRow} output with this header when writing a file.
         */
        public static final String CSV_HEADER =
                "config,benchmark,timeMs,memMb,"
                + "mayFailCasts,avgPtsSize,polyCallSites,reachableMethods,aliasPairs,objects,"
                + "costUsd,llmQueries";

        /**
         * Returns a single CSV data row for this metric snapshot.
         *
         * @param config     analysis configuration label (e.g. {@code "ci"}, {@code "2obj"})
         * @param benchmark  benchmark/program name
         * @param timeMs     wall-clock analysis time in milliseconds
         * @param memMb      peak heap usage in megabytes
         * @param costUsd    estimated LLM cost in USD (0 for baselines)
         * @param llmQueries number of LLM oracle calls made (0 for baselines)
         */
        public String toCsvRow(String config, String benchmark,
                                long timeMs, long memMb,
                                double costUsd, long llmQueries) {
            return String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.6f,%d,%d,%d,%d,%.6f,%d",
                    config, benchmark, timeMs, memMb,
                    mayFailCasts, avgPtsSize, polyCallSites,
                    reachableMethods, aliasPairs, objects,
                    costUsd, llmQueries);
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Collects all metrics from the given pointer-analysis result.
     *
     * @param result a completed {@link PointerAnalysisResult}
     * @return a populated {@link Metrics} record
     */
    public Metrics collect(PointerAnalysisResult result) {
        long mayFailCasts    = countMayFailCasts(result);
        double avgPtsSize    = computeAvgPtsSize(result);
        long polyCallSites   = countPolyCallSites(result);
        long reachableMethods = result.getCallGraph().getNumberOfMethods();
        long aliasPairs      = computeMayAliasPairs(result, result.getVars());
        long objects         = result.getObjects().size();
        return new Metrics(
                mayFailCasts, avgPtsSize, polyCallSites,
                reachableMethods, aliasPairs, objects);
    }

    // -----------------------------------------------------------------------
    // Private counters
    // -----------------------------------------------------------------------

    /**
     * Mirrors {@code pascal.taie.analysis.pta.client.MayFailCast}.
     *
     * <p>A cast statement {@code v = (T) u} is "may-fail" if any object in
     * {@code pts(u)} is <em>not</em> a subtype of {@code T}.
     */
    private long countMayFailCasts(PointerAnalysisResult result) {
        CallGraph<Invoke, JMethod> cg = result.getCallGraph();
        long count = 0;
        for (JMethod method : cg) {
            for (Stmt stmt : method.getIR()) {
                if (stmt instanceof Cast cast) {
                    Type castType = cast.getRValue().getCastType();
                    Var from = cast.getRValue().getValue();
                    for (Obj obj : result.getPointsToSet(from)) {
                        if (!World.get().getTypeSystem().isSubtype(castType, obj.getType())) {
                            count++;
                            break; // one failing object is enough to flag the site
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Mirrors {@code pascal.taie.analysis.pta.client.PolymorphicCallSite}.
     *
     * <p>A virtual or interface call site is "polymorphic" if the call graph
     * records more than one callee for it.
     */
    private long countPolyCallSites(PointerAnalysisResult result) {
        CallGraph<Invoke, JMethod> cg = result.getCallGraph();
        long count = 0;
        for (JMethod method : cg) {
            for (Stmt stmt : method.getIR()) {
                if (stmt instanceof Invoke invoke
                        && (invoke.isVirtual() || invoke.isInterface())) {
                    if (cg.getCalleesOf(invoke).size() > 1) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Mean size of {@code getPointsToSet(var)} over all vars with non-empty pts.
     *
     * <p>Returns {@code 0.0} when no variable has a non-empty points-to set.
     */
    private double computeAvgPtsSize(PointerAnalysisResult result) {
        Collection<Var> vars = result.getVars();
        long total = 0;
        long nonEmpty = 0;
        for (Var v : vars) {
            int size = result.getPointsToSet(v).size();
            if (size > 0) {
                total += size;
                nonEmpty++;
            }
        }
        return nonEmpty == 0 ? 0.0 : (double) total / nonEmpty;
    }

    /**
     * Mirrors {@code pascal.taie.analysis.pta.client.MayAliasPair}, computed over
     * <em>all</em> reachable variables (not restricted to application code).
     *
     * <p>{@code mayAlias(u, v)} iff there exists an object {@code o} such that
     * {@code o ∈ pts(u)} and {@code o ∈ pts(v)}.  Each unordered pair is
     * counted once (the raw count of alias relations is halved because
     * {@code (u→v)} and {@code (v→u)} are both accumulated).
     */
    private long computeMayAliasPairs(PointerAnalysisResult result,
                                      Collection<Var> vars) {
        Set<Var> varSet = new HashSet<>(vars);

        // Build obj → Set<Var> map so we can look up all vars sharing an object.
        Map<Obj, Set<Var>> obj2Vars = new HashMap<>();
        for (Var v : varSet) {
            for (Obj obj : result.getPointsToSet(v)) {
                obj2Vars.computeIfAbsent(obj, k -> new HashSet<>()).add(v);
            }
        }

        // For each var v, count how many OTHER vars share at least one object with v.
        long totalRelations = 0;
        for (Var v : varSet) {
            Set<Var> aliasVars = new HashSet<>();
            for (Obj o : result.getPointsToSet(v)) {
                Set<Var> shared = obj2Vars.getOrDefault(o, Collections.emptySet());
                aliasVars.addAll(shared);
            }
            aliasVars.remove(v); // a var does not alias with itself
            totalRelations += aliasVars.size();
        }
        // Each pair (u, v) is counted twice → divide by 2
        return totalRelations / 2;
    }
}
