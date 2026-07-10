/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pta.zeta1;

import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.OtherEdge;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ζ1 injection rung: treats the fixture library slice as an OPAQUE
 * boundary for the client analysis (slice bodies are ignored,
 * {@link Solver#addIgnoredMethod}), runs the untrusted oracle's proposal
 * through {@link SummaryChecker} (Definition II.24), and MATERIALIZES the
 * admitted summaries as pointer-analysis facts at every app-side call site
 * the solver derives — {@code mat(σ(m), cs, X)} of Definition II.25 at
 * fixture scale:
 * <ul>
 * <li>clause (a) for ret-alias: a pointer-flow edge actual → result;</li>
 * <li>clauses (a)+(f) for ret-fresh: the summary object {@code o_a},
 *     a mock object keyed by the GLOBAL site id alone (site-indexed,
 *     Definition II.14/B1), flows to the result;</li>
 * <li>clause (d) for callback: per receiver object the client derives for
 *     the actual, dispatch to the app override and add a call edge plus
 *     the receiver binding (app part only, Definition II.23).</li>
 * </ul>
 * If Σ is refused, NOTHING is injected (whole-Σ verdict): the slice stays
 * opaque and the client soundly sees the empty boundary — refusal costs
 * coverage, never soundness (§II.2.7).
 *
 * <p>Configuration is static because the solver instantiates plugins
 * reflectively; tests act as the policy layer wiring oracle → checker →
 * injection.
 */
public class SummaryInjectionPlugin implements Plugin {

    /** Descriptor of materialized summary objects o_a (Definition II.14). */
    private static final Descriptor SUMMARY_OBJ_DESC = () -> "SummaryObj";

    // ---------- static configuration (set by the test harness) ----------

    private static List<String> sliceClasses = List.of();

    private static SummaryOracle oracle;

    private static CheckReport lastReport;

    private static final List<String> injectionLog = new ArrayList<>();

    /**
     * Configures the verifier rung for the next analysis run.
     *
     * @param classes simple names of the slice classes (S)
     * @param summaryOracle the untrusted proposer (the LLM slot)
     */
    public static void configure(List<String> classes,
                                 SummaryOracle summaryOracle) {
        sliceClasses = List.copyOf(classes);
        oracle = summaryOracle;
        lastReport = null;
        injectionLog.clear();
    }

    /** @return the checker verdict of the last analysis run. */
    public static CheckReport getLastReport() {
        return lastReport;
    }

    /** @return deterministic log of materialized facts (last run). */
    public static List<String> getInjectionLog() {
        return List.copyOf(injectionLog);
    }

    // ---------- per-run state ----------

    private Solver solver;

    private CSManager csManager;

    private ClassHierarchy hierarchy;

    private Map<JMethod, Set<SummaryAtom>> admitted = Map.of();

    /** Callback watches: argument variable → (call site, SAM, receiver). */
    private final Map<CSVar, Set<CallbackWatch>> watches = new LinkedHashMap<>();

    private record CallbackWatch(CSCallSite csCallSite, JMethod sam) {
    }

    /** Marker for summary-materialized pointer flows (ret-alias). */
    private static class SummaryAliasEdge extends OtherEdge {
        SummaryAliasEdge(CSVar source, CSVar target) {
            super(source, target);
        }
    }

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
        this.hierarchy = solver.getHierarchy();
    }

    @Override
    public void onStart() {
        admitted = Map.of();
        watches.clear();
        // The slice is the boundary: its bodies are never analyzed by the
        // client — only the checker reads them (X-independence, IF-1).
        List<JMethod> sliceMethods = new ArrayList<>();
        for (String className : sliceClasses) {
            JClass clazz = hierarchy.getClass(className);
            if (clazz != null) {
                clazz.getDeclaredMethods().forEach(m -> {
                    solver.addIgnoredMethod(m);
                    sliceMethods.add(m);
                });
            }
        }
        if (oracle == null) {
            return;
        }
        // oracle proposes; the checker admits post-fixpoints only
        List<String> signatures = sliceMethods.stream()
                .map(JMethod::getSignature).sorted().toList();
        Map<String, Set<SummaryAtom>> proposed = oracle.propose(signatures);
        SummaryChecker checker = new SummaryChecker(hierarchy,
                new LinkedHashSet<>(sliceClasses));
        lastReport = checker.check(proposed);
        if (lastReport.admitted()) {
            Map<JMethod, Set<SummaryAtom>> resolved = new LinkedHashMap<>();
            proposed.forEach((sig, atoms) -> sliceMethods.stream()
                    .filter(m -> m.getSignature().equals(sig))
                    .findFirst()
                    .ifPresent(m -> resolved.put(m, atoms)));
            admitted = resolved;
        }
    }

    /**
     * An app-side call site of a summarized method has been derived:
     * materialize the admitted atoms at it (Definition II.25).
     */
    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (edge.getKind() == CallKind.OTHER) {
            return; // do not re-inject on our own materialized edges
        }
        Set<SummaryAtom> atoms = admitted.get(edge.getCallee().getMethod());
        if (atoms == null) {
            return;
        }
        CSCallSite csCallSite = edge.getCallSite();
        Context context = csCallSite.getContext();
        Invoke callSite = csCallSite.getCallSite();
        Var result = callSite.getResult();
        for (SummaryAtom atom : atoms) {
            if (atom instanceof SummaryAtom.RetAlias retAlias) {
                if (result != null) {
                    Var actual = callSite.getInvokeExp()
                            .getArg(retAlias.paramIdx());
                    solver.addPFGEdge(new SummaryAliasEdge(
                            csManager.getCSVar(context, actual),
                            csManager.getCSVar(context, result)));
                    injectionLog.add("ret-alias @ " + callSite
                            + " : " + actual.getName()
                            + " -> " + result.getName());
                }
            } else if (atom instanceof SummaryAtom.RetFresh retFresh) {
                if (result != null) {
                    JClass clazz = hierarchy.getClass(retFresh.className());
                    Type type = clazz.getType();
                    // o_a: keyed by the GLOBAL site id alone — the same
                    // site in different summaries is the same object (B1)
                    Obj summaryObj = solver.getHeapModel().getMockObj(
                            SUMMARY_OBJ_DESC, retFresh.siteId(), type);
                    solver.addVarPointsTo(context, result, summaryObj);
                    injectionLog.add("ret-fresh @ " + callSite
                            + " : " + summaryObj);
                }
            } else if (atom instanceof SummaryAtom.Callback callback) {
                Var actual = callSite.getInvokeExp()
                        .getArg(callback.paramIdx());
                JMethod sam = resolveSam(actual.getType(),
                        callback.samSubsignature());
                if (sam == null) {
                    continue; // malformed atoms cannot be admitted
                }
                CSVar csActual = csManager.getCSVar(context, actual);
                CallbackWatch watch = new CallbackWatch(csCallSite, sam);
                watches.computeIfAbsent(csActual,
                        v -> new LinkedHashSet<>()).add(watch);
                // replay receiver objects that arrived before the watch
                solver.getPointsToSetOf(csActual).forEach(recv ->
                        dispatchCallback(watch, recv));
            }
        }
    }

    /** New receiver objects for a watched callback argument. */
    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Set<CallbackWatch> varWatches = watches.get(csVar);
        if (varWatches != null) {
            for (CallbackWatch watch : List.copyOf(varWatches)) {
                pts.forEach(recv -> dispatchCallback(watch, recv));
            }
        }
    }

    /**
     * mat clause (d) (Definition II.25): dispatch the SAM on the receiver
     * object's class; app overrides only (the app part of the cone,
     * Definition II.23 — the lib part is case (i)/(iii), out of ζ1 scope).
     */
    private void dispatchCallback(CallbackWatch watch, CSObj recv) {
        JMethod target = hierarchy.dispatch(
                recv.getObject().getType(), watch.sam().getRef());
        if (target == null || !target.getDeclaringClass().isApplication()) {
            return;
        }
        Context calleeContext = solver.getContextSelector()
                .selectContext(watch.csCallSite(), recv, target);
        solver.addCallEdge(new Edge<>(CallKind.OTHER, watch.csCallSite(),
                csManager.getCSMethod(calleeContext, target)));
        // receiver binding: the callback runs on the receiver object
        solver.addVarPointsTo(calleeContext,
                target.getIR().getThis(), recv);
        injectionLog.add("callback @ " + watch.csCallSite().getCallSite()
                + " : -> " + target);
    }

    /** Resolves the SAM from the declared type of the callback argument. */
    private JMethod resolveSam(Type declaredType, String samSubsignature) {
        JClass clazz = hierarchy.getClass(declaredType.getName());
        Subsignature subsignature = Subsignature.get(samSubsignature);
        while (clazz != null) {
            JMethod method = clazz.getDeclaredMethod(subsignature);
            if (method != null) {
                return method;
            }
            for (JClass iface : clazz.getInterfaces()) {
                JMethod ifaceMethod = iface.getDeclaredMethod(subsignature);
                if (ifaceMethod != null) {
                    return ifaceMethod;
                }
            }
            clazz = clazz.getSuperClass();
        }
        return null;
    }
}
