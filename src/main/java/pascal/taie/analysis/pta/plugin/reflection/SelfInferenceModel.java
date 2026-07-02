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

package pascal.taie.analysis.pta.plugin.reflection;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.AnalysisModelPlugin;
import pascal.taie.analysis.pta.plugin.util.CSObjs;
import pascal.taie.analysis.pta.plugin.util.InvokeHandler;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;

import java.util.List;
import java.util.Set;

import static pascal.taie.analysis.pta.plugin.util.InvokeUtils.BASE;

/**
 * Deterministic self-inferencing links that Tai-e's reflection models leave open, and
 * which dominate the DaCapo reflection-recall gap (e.g. lucene's SegmentReader /
 * FSDirectory). No LLM involved — this is Elf-style collective inference.
 *
 * <ul>
 *   <li>{@code Class.getName()} on a resolved {@code Class} metaobject → a string
 *       constant of the class's (binary) name. This lets the classic
 *       {@code DefaultClass.class.getName()} default reach a downstream
 *       {@code Class.forName}.</li>
 *   <li>{@code System.getProperty(key, default)} → {@code default ⊔ ⊤} (G7). The call
 *       returns the {@code default} only if the property is unset; if set at runtime
 *       (env / {@code -Dkey=…} / config) it returns THAT value. Flowing only the default
 *       is UNDER-approximate (unsound) — it misses the runtime-set class. So we flow the
 *       default AND an Unknown string {@code ⊤}, which routes a downstream
 *       {@code Class.forName} to the residual/oracle (the disposer then resolves or flags
 *       it). The default still lets {@code forName(getProperty(key, X.class.getName()))}
 *       resolve {@code X}.</li>
 * </ul>
 *
 * Both are sound (only add points-to facts; {@code getProperty} now over- not
 * under-approximates its result).
 */
public class SelfInferenceModel extends AnalysisModelPlugin {

    private static final Descriptor CLASS_NAME = () -> "ClassNameConstant";

    /** ⊤ for G7: an Unknown string (a runtime property value could be anything). */
    private static final Descriptor UNKNOWN_PROP = () -> "UnknownRuntimeProperty";

    /**
     * Two-arg {@code getProperty} sites awaiting the SITE-LEVEL ⊤ (S5). The ⊤ must not be
     * gated on the default argument's points-to (the arg-indexed handler never fires when
     * the default is opaque, e.g. a StringBuilder result) — it is emitted once per
     * reachable site, from {@link #onPhaseFinish()}.
     */
    private final Set<Invoke> propSites = Sets.newSet();

    private final Set<Invoke> topSeeded = Sets.newSet();

    SelfInferenceModel(Solver solver) {
        super(solver);
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        super.onNewStmt(stmt, container);
        if (stmt instanceof Invoke invoke && !invoke.isDynamic()
                && invoke.getResult() != null) {
            MethodRef ref = invoke.getMethodRef();
            if (ref.getName().equals("getProperty")
                    && ref.getDeclaringClass().getName().equals("java.lang.System")
                    && ref.getParameterTypes().size() == 2) {
                propSites.add(invoke);
            }
        }
    }

    @Override
    public void onPhaseFinish() {
        for (Invoke site : propSites) {
            if (!topSeeded.contains(site)) {
                seedTop(site);
            }
        }
    }

    /** Emits the ⊤ Unknown string at a reachable two-arg getProperty site (G7/S5). */
    private void seedTop(Invoke site) {
        JMethod container = site.getContainer();
        List<Context> ctxs = solver.getCallGraph().reachableMethods()
                .filter(m -> m.getMethod().equals(container))
                .map(CSMethod::getContext)
                .toList();
        if (ctxs.isEmpty()) {
            return; // container not reachable yet; retry next phase
        }
        Obj top = solver.getHeapModel().getMockObj(UNKNOWN_PROP, site,
                solver.getTypeSystem().stringType(), container);
        for (Context c : ctxs) {
            solver.addVarPointsTo(c, site.getResult(),
                    solver.getCSManager().getCSObj(c, top));
        }
        topSeeded.add(site);
    }

    @InvokeHandler(signature = "<java.lang.Class: java.lang.String getName()>",
            argIndexes = {BASE})
    public void classGetName(Context context, Invoke invoke, PointsToSet classObjs) {
        Var result = invoke.getResult();
        if (result == null) {
            return;
        }
        classObjs.forEach(co -> {
            JClass c = CSObjs.toClass(co);
            if (c == null) {
                return;
            }
            // A mock obj whose ALLOCATION is a StringLiteral: CSObjs.toString reads it
            // back as the class name, so a downstream forName resolves it (bypasses the
            // string-constant merging that would otherwise hide a synthesized constant).
            Obj nameObj = solver.getHeapModel().getMockObj(CLASS_NAME,
                    StringLiteral.get(c.getName()),
                    solver.getTypeSystem().stringType(), invoke.getContainer());
            solver.addVarPointsTo(context, result, nameObj);
        });
    }

    @InvokeHandler(signature =
            "<java.lang.System: java.lang.String getProperty(java.lang.String,java.lang.String)>",
            argIndexes = {1})
    public void getPropertyDefault(Context context, Invoke invoke, PointsToSet defaults) {
        Var result = invoke.getResult();
        if (result == null) {
            return;
        }
        // Flow the default (the value when the property is unset). The G7 ⊤ — "the
        // property may be SET at runtime to any value" — is emitted SITE-LEVEL from
        // onPhaseFinish (seedTop), NOT here: this handler fires only when the default
        // arg's pts is non-empty, which is exactly false for opaque defaults (S5).
        defaults.forEach(d -> solver.addVarPointsTo(context, result, d));
    }
}
