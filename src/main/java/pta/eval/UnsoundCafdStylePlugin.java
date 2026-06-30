package pta.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
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
 * INTENTIONALLY UNSOUND strawman plugin — this is NOT one of our proposed arms.
 *
 * <p>This plugin models the "LLM on the soundness critical path" design found in
 * CAFD-style pointer-analysis augmentations. It is used in {@link RobustnessSweep}
 * to <em>contrast</em> with our sound arms: as the LLM error rate rises, recall
 * falls below 1.0 because real call edges are dropped.
 *
 * <h2>How it differs from arm ③ (sound heap cloning, {@code advanced:llm-cafd})</h2>
 * <p>Arm ③ never lets the LLM act unchecked: an LLM-proposed method is cloned
 * only if B3's structural {@link pta.baseline.cafd.WrapperDetector} independently
 * confirms it is a genuine fresh-allocation wrapper, and cloning only ever adds
 * abstract objects (it cannot drop an edge). This plugin, by contrast, applies
 * all LLM-proposed facts directly with NO structural or consistency check. A
 * self-contradictory fact ({@code never-alias ARunner ARunner}) therefore filters
 * the actual allocated type from a wrapper result variable, causing a real
 * virtual-dispatch edge to be missed — soundness is lost.
 *
 * <h2>Oracle injection</h2>
 * <p>Like arm ②, the oracle is injected via {@link #setOracle}/{@link #clearOracle}
 * before each run. When no override is set the plugin is a no-op (offline-safe).
 */
public class UnsoundCafdStylePlugin implements Plugin {

    private static final Logger logger =
            LoggerFactory.getLogger(UnsoundCafdStylePlugin.class);

    // -----------------------------------------------------------------------
    // Static oracle override (same pattern as arm2.LlmReflectionModel)
    // -----------------------------------------------------------------------

    private static volatile LlmOracle oracleOverride;

    /**
     * Injects an oracle that will be used by the next analysis run.
     * Must be paired with {@link #clearOracle()} in a {@code finally} block.
     */
    public static void setOracle(LlmOracle oracle) {
        oracleOverride = oracle;
    }

    /** Removes the injected oracle (called after each sweep run). */
    public static void clearOracle() {
        oracleOverride = null;
    }

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private Solver solver;

    private LlmOracle oracle;

    private CSManager csManager;

    /** container method → (result variable → set of group names to exclude). */
    private final Map<JMethod, Map<Var, Set<String>>> filterPlan = new HashMap<>();

    // -----------------------------------------------------------------------
    // Plugin lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.oracle = oracleOverride;
        if (oracle == null) {
            return; // no oracle → no-op (offline-safe)
        }
        this.csManager = solver.getCSManager();
        plan(solver);
    }

    /**
     * Builds the filter plan at setup time (before any entry method is registered).
     *
     * <p>Unlike arm ③ this method applies ALL oracle-proposed facts without:
     * <ul>
     *   <li>Structural identity verification of wrapper methods</li>
     *   <li>LlmWrapperProposer / WrapperDetector structural confirmation (advanced:llm-cafd)</li>
     * </ul>
     */
    private void plan(Solver solver) {
        List<JMethod> appMethods = new ArrayList<>();
        solver.getHierarchy().applicationClasses()
                .forEach(c -> appMethods.addAll(c.getDeclaredMethods()));

        // Collect alloc groups and method names for the prompt.
        Set<String> groups = new LinkedHashSet<>();
        Set<String> methodNames = new LinkedHashSet<>();
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
            methodNames.add(m.getName());
        }

        // Query oracle — same prompt format as arm ③ for comparability.
        String prompt = "Given alloc-groups " + groups + " and methods " + methodNames
                + ", reply with lines of 'never-alias <A> <B>' or 'wrapper <method>'.";
        LlmResponse resp = oracle.ask(new LlmQuery("alias-fact", prompt, "unsound-cafd"));

        // Parse proposals — NO structural check (this is the unsoundness; arm③ would
        // confirm via LlmWrapperProposer + WrapperDetector before applying).
        Map<String, Set<String>> excluded = new HashMap<>();
        Set<String> wrappers = new HashSet<>();
        for (String line : resp.asLines()) {
            String[] tok = line.trim().split("\\s+");
            if (tok.length == 3 && "never-alias".equals(tok[0])) {
                excluded.computeIfAbsent(tok[1], k -> new HashSet<>()).add(tok[2]);
                excluded.computeIfAbsent(tok[2], k -> new HashSet<>()).add(tok[1]);
            } else if (tok.length == 2 && "wrapper".equals(tok[0])) {
                wrappers.add(tok[1]);
            }
        }
        if (excluded.isEmpty() && wrappers.isEmpty()) {
            logger.debug("[UnsoundCafd] oracle returned no actionable facts — no-op");
            return;
        }

        // Plan pointer filters at wrapper call sites.
        // NO structural identity check: we trust the oracle's wrapper claim entirely.
        for (JMethod container : appMethods) {
            if (container.isAbstract()) {
                continue;
            }
            IR ir = container.getIR();
            Map<Var, String> groupOf = allocGroups(ir);
            for (Stmt s : ir.getStmts()) {
                if (!(s instanceof Invoke inv)) {
                    continue;
                }
                if (!wrappers.contains(inv.getMethodRef().getName())) {
                    continue;
                }
                if (inv.getResult() == null || inv.getInvokeExp().getArgCount() != 1) {
                    continue;
                }
                Var arg = inv.getInvokeExp().getArg(0);
                String argGroup = groupOf.get(arg);
                if (argGroup == null) {
                    continue;
                }
                Set<String> ban = excluded.get(argGroup);
                if (ban != null && !ban.isEmpty()) {
                    filterPlan
                            .computeIfAbsent(container, k -> new HashMap<>())
                            .computeIfAbsent(inv.getResult(), k -> new HashSet<>())
                            .addAll(ban);
                }
            }
        }
        logger.info("[UnsoundCafd] filter plan: {} method(s) — UNSOUND (no structural or consistency checks)",
                filterPlan.size());
    }

    /**
     * Installs the planned pointer filters when a CS method becomes reachable,
     * before its body is processed — identical timing to arm ③.
     */
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
                    (CSObj o) -> !banned.contains(
                            simpleName(o.getObject().getType().getName())));
        });
    }

    // -----------------------------------------------------------------------
    // Helpers (self-contained; mirrors simple type-name extraction logic)
    // -----------------------------------------------------------------------

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    /**
     * Maps each variable in {@code ir} to its allocation-group simple name,
     * propagating through copy statements.
     */
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
                if (s instanceof Copy c
                        && group.containsKey(c.getRValue())
                        && !group.containsKey(c.getLValue())) {
                    group.put(c.getLValue(), group.get(c.getRValue()));
                    changed = true;
                }
            }
        }
        return group;
    }
}
