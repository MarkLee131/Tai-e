package pta.arm3;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pta.arm3.datalog.ConsistencyEngine;
import pta.llm.LlmOracle;
import pta.llm.LlmQuery;
import pta.llm.LlmResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arm 3 — neuro-symbolic plugin. The LLM proposes high-level <em>defeasible</em>
 * facts (never-alias between alloc-groups; identity-transparent wrappers). A
 * logic {@link ConsistencyEngine} rejects any fact that contradicts the sound
 * base model; only survivors refine the analysis, applied as sound-by-
 * consistency pointer filters via {@link Solver#addPointerFilter}.
 *
 * <p>Pipeline (run once in {@link #setSolver}, before the solver registers any
 * entry method, so no de-conflatable site is missed):
 * <ol>
 *   <li>Scan application IR for candidate alloc-groups (created type names) and
 *       identity-wrapper methods (single-param methods that return their
 *       parameter).</li>
 *   <li>Build the sound base alias relation (reflexive: every group aliases
 *       itself) and query the oracle for high-level facts.</li>
 *   <li>{@link ConsistencyEngine#accept} arbitrates the never-alias proposals.</li>
 *   <li>For each accepted never-alias(A,B) and each call {@code lhs = wrapper(arg)}
 *       where {@code arg} is allocated as group A, plan a filter on {@code lhs}
 *       that excludes group-B objects (and vice versa).</li>
 * </ol>
 * Filters are installed lazily in {@link #onNewCSMethod} — exactly when the
 * containing method first becomes context-sensitively reachable, before its
 * body propagates — mirroring the taint {@code SanitizerHandler} pattern, so
 * the refinement actually takes effect on the final points-to sets.
 *
 * <p>Registered via the {@code pta} option {@code plugins:[pta.arm3.LlmFactPlugin]}.
 */
public class LlmFactPlugin implements Plugin {

    private Solver solver;

    private CSManager csManager;

    /** container method -> (result var -> excluded group simple-names). */
    private final Map<JMethod, Map<Var, Set<String>>> filterPlan = new HashMap<>();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        // Plan eagerly: the solver registers entry methods (firing
        // onNewCSMethod) inside the builtin EntryPointHandler.onStart(), which
        // runs before THIS plugin's onStart(). setSolver runs before solve(),
        // so the plan is ready when the first onNewCSMethod arrives.
        plan();
    }

    private void plan() {
        LlmOracle oracle = ArmOracleFactory.fromOptions(solver.getOptions());
        if (oracle == null) {
            return; // no oracle configured -> plugin is a no-op (sound)
        }
        this.csManager = solver.getCSManager();

        List<JMethod> appMethods = new ArrayList<>();
        solver.getHierarchy().applicationClasses().forEach(c ->
                appMethods.addAll(c.getDeclaredMethods()));

        Set<String> groups = new LinkedHashSet<>();
        Set<String> wrapperMethods = new LinkedHashSet<>();
        for (JMethod m : appMethods) {
            if (m.isAbstract()) {
                continue;
            }
            IR ir = m.getIR();
            for (Stmt s : ir.getStmts()) {
                if (s instanceof New n) {
                    groups.add(simpleName(n.getRValue().getType().getName()));
                }
            }
            if (isIdentityWrapper(m)) {
                wrapperMethods.add(m.getName());
            }
        }

        // Sound base alias relation: reflexive (every group aliases itself).
        List<AliasFact> base = new ArrayList<>();
        for (String g : groups) {
            base.add(new AliasFact(g, g, true));
        }

        // Query the oracle and parse defeasible facts.
        String prompt = buildPrompt(groups, wrapperMethods);
        LlmResponse resp = oracle.ask(new LlmQuery("alias-fact", prompt, "arm3"));
        List<AliasFact> proposedAlias = new ArrayList<>();
        Set<String> proposedWrappers = new HashSet<>();
        for (String line : resp.asLines()) {
            String[] tok = line.trim().split("\\s+");
            if (tok.length == 3 && tok[0].equals("never-alias")) {
                proposedAlias.add(new AliasFact(tok[1], tok[2], false));
            } else if (tok.length == 2 && tok[0].equals("wrapper")) {
                proposedWrappers.add(tok[1]);
            }
        }

        // Logic arbitration: reject never-alias facts contradicting the base.
        ConsistencyEngine engine = new ConsistencyEngine();
        List<AliasFact> survivors = engine.accept(base, proposedAlias);

        // group -> groups it is (symmetrically) declared never to alias with.
        Map<String, Set<String>> excluded = new HashMap<>();
        for (AliasFact f : survivors) {
            if (!f.mayAlias()) {
                excluded.computeIfAbsent(f.groupA(), k -> new HashSet<>())
                        .add(f.groupB());
                excluded.computeIfAbsent(f.groupB(), k -> new HashSet<>())
                        .add(f.groupA());
            }
        }

        // Only act on wrappers that the LLM proposed AND are structurally
        // identity-transparent (the defeasible judgment, checked).
        Set<String> enabledWrappers = new HashSet<>(proposedWrappers);
        enabledWrappers.retainAll(wrapperMethods);

        // Plan filters at each de-conflatable wrapper call site.
        for (JMethod container : appMethods) {
            if (container.isAbstract()) {
                continue;
            }
            IR ir = container.getIR();
            Map<Var, String> groupOf = allocGroups(ir);
            for (Stmt s : ir.getStmts()) {
                if (s instanceof Invoke inv
                        && enabledWrappers.contains(inv.getMethodRef().getName())
                        && inv.getResult() != null
                        && inv.getInvokeExp().getArgCount() == 1) {
                    Var arg = inv.getInvokeExp().getArg(0);
                    String argGroup = groupOf.get(arg);
                    if (argGroup == null) {
                        continue;
                    }
                    Set<String> ban = excluded.get(argGroup);
                    if (ban != null && !ban.isEmpty()) {
                        filterPlan.computeIfAbsent(container, k -> new HashMap<>())
                                .computeIfAbsent(inv.getResult(), k -> new HashSet<>())
                                .addAll(ban);
                    }
                }
            }
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        Map<Var, Set<String>> plan = filterPlan.get(csMethod.getMethod());
        if (plan == null) {
            return;
        }
        Context ctx = csMethod.getContext();
        plan.forEach((var, banned) -> {
            CSVar csVar = csManager.getCSVar(ctx, var);
            solver.addPointerFilter(csVar,
                    (CSObj o) -> !banned.contains(simpleName(
                            o.getObject().getType().getName())));
        });
    }

    /** A method is an identity wrapper if it has one parameter and every
     *  return statement returns (a copy of) that parameter. */
    private static boolean isIdentityWrapper(JMethod m) {
        if (m.getParamCount() != 1 || m.isAbstract()) {
            return false;
        }
        IR ir = m.getIR();
        Var param = ir.getParam(0);
        // forward-propagate the param tag through copies
        Set<Var> isParam = new HashSet<>();
        isParam.add(param);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Stmt s : ir.getStmts()) {
                if (s instanceof Copy c && isParam.contains(c.getRValue())
                        && isParam.add(c.getLValue())) {
                    changed = true;
                }
            }
        }
        boolean hasReturn = false;
        for (Stmt s : ir.getStmts()) {
            if (s instanceof Return r) {
                hasReturn = true;
                if (r.getValue() == null || !isParam.contains(r.getValue())) {
                    return false;
                }
            }
        }
        return hasReturn;
    }

    /** Maps each var to its allocation-group simple name, tracing through
     *  {@code new} statements and copy chains within one method. */
    private static Map<Var, String> allocGroups(IR ir) {
        Map<Var, String> group = new HashMap<>();
        for (Stmt s : ir.getStmts()) {
            if (s instanceof New n) {
                group.put(n.getLValue(),
                        simpleName(n.getRValue().getType().getName()));
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Stmt s : ir.getStmts()) {
                if (s instanceof Copy c && group.containsKey(c.getRValue())
                        && !group.containsKey(c.getLValue())) {
                    group.put(c.getLValue(), group.get(c.getRValue()));
                    changed = true;
                }
            }
        }
        return group;
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private static String buildPrompt(Set<String> groups, Set<String> wrappers) {
        return "Given alloc-groups " + groups + " and candidate identity"
                + " wrappers " + wrappers + ", reply with lines of either"
                + " 'never-alias <A> <B>' or 'wrapper <method>'.";
    }
}
