package pta.baseline.cafd;

import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

/**
 * Companion plugin for {@link AllocatorWrapperModel}.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><b>Suppression</b> ({@link #onStart}): registers each detected
 *       wrapper method as an "ignored" method so the solver does not analyse
 *       its body. This prevents the allocation sites inside wrapper methods
 *       from being processed, ensuring that return values are determined
 *       solely by the per-callsite MockObjs below.</li>
 *   <li><b>Per-callsite injection</b> ({@link #onNewCallEdge}): whenever a
 *       new call edge to a wrapper is discovered, retrieves the corresponding
 *       per-callsite MockObj from the heap model and adds it to the points-to
 *       set of the caller's return variable. This achieves per-callsite heap
 *       cloning without full context sensitivity.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * Registered and used by a single-threaded solver; no additional
 * synchronisation is required.
 */
public class AllocatorWrapperPlugin implements Plugin {

    private final AllocatorWrapperModel model;

    private Solver solver;

    public AllocatorWrapperPlugin(AllocatorWrapperModel model) {
        this.model = model;
    }

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    /**
     * Suppresses the normal body analysis of every detected wrapper method.
     * This must be done in {@code onStart()} — before the solver's work list
     * is processed — so that the solver skips the wrapper body when it first
     * becomes reachable.
     */
    @Override
    public void onStart() {
        for (JMethod wrapper : model.getWrappers()) {
            solver.addIgnoredMethod(wrapper);
        }
    }

    /**
     * When a new call edge to a wrapper is discovered, injects the
     * per-callsite abstract object into the caller's return variable.
     *
     * <p>The solver has already skipped the normal argument/return-value
     * flow for the ignored callee; we replace it here with a direct
     * variable → MockObj assignment.
     */
    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        JMethod callee = edge.getCallee().getMethod();
        if (!model.isWrapper(callee)) {
            return;
        }
        CSCallSite csCallSite = edge.getCallSite();
        Invoke invoke = csCallSite.getCallSite();
        Obj obj = model.getOrCreateCallSiteObj(invoke, callee);
        if (obj == null) {
            return;
        }
        Var lhs = invoke.getResult();
        if (lhs != null) {
            solver.addVarPointsTo(csCallSite.getContext(), lhs, obj);
        }
    }
}
