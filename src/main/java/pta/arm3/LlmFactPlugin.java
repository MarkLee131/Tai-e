package pta.arm3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Obj;
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
 * logic disposer guarantees soundness: a wrong — or accepted-but-incorrect —
 * LLM answer may only cost precision/time, never drop a real object (hence never
 * drop a real call edge / alias).
 *
 * <h3>Soundness mechanism ("LLM proposes, sound logic disposes")</h3>
 * <p>Arm ③ is wired as a TWO-PASS analysis (mirroring arm ① / B3): a sound
 * context-insensitive (CI) PRE-ANALYSIS produces a {@link PointerAnalysisResult}
 * that the disposer consults at two points, so refinement is gated by the sound
 * points-to relation rather than by purely-syntactic heuristics:
 * <ol>
 *   <li><b>Consistency base from the real analysis.</b> The
 *       {@link ConsistencyEngine} base is built from the REAL group co-occurrence
 *       of the CI pre-analysis: two alloc-groups "may-alias" iff some variable's
 *       CI points-to set contains objects of both groups. (Variables whose CI
 *       conflation is itself wrapper-induced — an identity wrapper's formal
 *       parameter and its call-site results — are excluded, because that
 *       conflation is precisely the imprecision arm ③ is licensed to refine and
 *       must not be read back as evidence of real aliasing.) A
 *       {@code never-alias(A,B)} that contradicts this base (the groups truly
 *       co-occur at a non-wrapper variable) is REJECTED.</li>
 *   <li><b>CI-gated filter install.</b> For an accepted {@code never-alias(A,B)}
 *       and a call {@code lhs = wrapper(arg)}, a group is banned from {@code lhs}
 *       ONLY IF the sound CI points-to set of the call-site argument {@code arg}
 *       provably does not contain an object of that group. Because the wrapper is
 *       structurally an identity wrapper, {@code pts_real(lhs) ⊆ pts_real(arg) ⊆
 *       pts_CI(arg)}; so banning a group absent from {@code pts_CI(arg)} can
 *       never remove a real object. Any group {@code arg} may hold per CI is
 *       removed from the ban (the filter is WITHHELD for it). This is the
 *       load-bearing soundness guarantee — it does not trust the syntactic
 *       alloc-group, which misses re-assignment / method-return / cast inflows.</li>
 * </ol>
 *
 * <p>Filters are installed lazily in {@link #onNewCSMethod} — when the containing
 * method first becomes reachable, before its body propagates — mirroring the
 * taint {@code SanitizerHandler} pattern, so the refinement takes effect on the
 * final points-to sets.
 *
 * <p>Registered via {@code plugins:[pta.arm3.LlmFactPlugin]}; the CI pre-analysis
 * is wired transparently in {@link pascal.taie.analysis.pta.PointerAnalysis}.
 */
public class LlmFactPlugin implements Plugin {

    private static final Logger logger =
            LoggerFactory.getLogger(LlmFactPlugin.class);

    private Solver solver;

    private CSManager csManager;

    /** Sound CI pre-analysis result consulted by the disposer. */
    private final PointerAnalysisResult preResult;

    /** container method -> (result var -> excluded group simple-names). */
    private final Map<JMethod, Map<Var, Set<String>>> filterPlan = new HashMap<>();

    /**
     * @param preResult sound context-insensitive pre-analysis result. Used both
     *                  to build the consistency base (real group co-occurrence)
     *                  and to gate each filter on the sound points-to relation.
     */
    public LlmFactPlugin(PointerAnalysisResult preResult) {
        this.preResult = preResult;
    }

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

        // Only act on wrappers that the LLM proposed AND are structurally
        // identity-transparent (the defeasible judgment, checked).
        Set<String> enabledWrappers = new HashSet<>(proposedWrappers);
        enabledWrappers.retainAll(wrapperMethods);

        // Variables whose CI points-to set is a WRAPPER-INDUCED conflation: an
        // identity wrapper's formal parameter and the result var of each
        // enabled-wrapper call site. Their CI co-occurrence is exactly the
        // imprecision arm③ de-conflates, so it must NOT count as real aliasing
        // when building the consistency base.
        Set<Var> wrapperConflated = new HashSet<>();
        for (JMethod m : appMethods) {
            if (!m.isAbstract() && m.getParamCount() == 1
                    && enabledWrappers.contains(m.getName())) {
                wrapperConflated.add(m.getIR().getParam(0));
            }
        }
        for (JMethod container : appMethods) {
            if (container.isAbstract()) {
                continue;
            }
            for (Stmt s : container.getIR().getStmts()) {
                if (s instanceof Invoke inv
                        && enabledWrappers.contains(inv.getMethodRef().getName())
                        && inv.getResult() != null) {
                    wrapperConflated.add(inv.getResult());
                }
            }
        }

        // Sound base alias relation: REAL group co-occurrence of the CI
        // pre-analysis (two groups may-alias iff some non-wrapper-conflated var's
        // CI points-to set holds objects of both). Reflexive by construction of
        // the engine's union-find. A never-alias contradicting this is rejected.
        List<AliasFact> base = new ArrayList<>();
        for (Var v : preResult.getVars()) {
            if (wrapperConflated.contains(v)) {
                continue;
            }
            List<String> gs = new ArrayList<>(groupsOf(preResult.getPointsToSet(v)));
            for (int i = 0; i < gs.size(); i++) {
                for (int j = i + 1; j < gs.size(); j++) {
                    base.add(new AliasFact(gs.get(i), gs.get(j), true));
                }
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

        // Plan filters at each de-conflatable wrapper call site, CI-GATED.
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
                    if (ban == null || ban.isEmpty()) {
                        continue;
                    }
                    // CI GATE: a group may be banned from the wrapper result only
                    // if the SOUND CI points-to set of the argument provably does
                    // not contain it. Since the wrapper is an identity wrapper,
                    // pts_real(lhs) ⊆ pts_real(arg) ⊆ pts_CI(arg); banning a group
                    // absent from pts_CI(arg) cannot remove a real object. Groups
                    // that arg may hold per CI are withheld from the ban.
                    Set<String> argCiGroups = groupsOf(preResult.getPointsToSet(arg));
                    Set<String> safeBan = new HashSet<>(ban);
                    safeBan.removeAll(argCiGroups);
                    if (safeBan.isEmpty()) {
                        logger.info("[arm3] withholding never-alias filter on {} in {}: "
                                        + "arg CI pts {} overlaps the proposed ban {} "
                                        + "(soundness over precision)",
                                inv.getResult(), container.getName(), argCiGroups, ban);
                        continue;
                    }
                    filterPlan.computeIfAbsent(container, k -> new HashMap<>())
                            .computeIfAbsent(inv.getResult(), k -> new HashSet<>())
                            .addAll(safeBan);
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

    /** Simple-name alloc-group set of a CI points-to set. */
    private static Set<String> groupsOf(Set<Obj> pts) {
        Set<String> gs = new HashSet<>();
        for (Obj o : pts) {
            gs.add(simpleName(o.getType().getName()));
        }
        return gs;
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
