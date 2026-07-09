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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.CSObjs;
import pascal.taie.analysis.pta.plugin.util.InvokeHandler;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pta.llm.LlmOracle;
import pta.llm.LlmQuery;

import pascal.taie.util.collection.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static pascal.taie.analysis.pta.plugin.util.InvokeUtils.BASE;

/**
 * Arm② — LLM-augmented reflection inference (integrated into Tai-e's reflection
 * stack). Resolves reflective NAMES like {@link StringBasedModel} for string
 * constants, and additionally asks an {@link LlmOracle} for the residual sites
 * whose class/method name is input-dependent ("Unknown"), where static
 * resolution fails. Resolved names are turned into Class/Method metaobjects via
 * the inherited {@code classForNameKnown}/{@code classGetMethodKnown}, so
 * {@link ReflectiveActionModel} builds the downstream newInstance/invoke/get/set
 * edges soundly (incl. argument flow, via {@code ReflectiveCallEdge}), and
 * {@link TypeMatcher} clamps targets by the use-site downcast/argument types.
 *
 * <p><b>Sound by construction:</b> the LLM only ADDS candidate names; an
 * unloadable name resolves to nothing ({@code hierarchy.getClass} returns null),
 * and the existing type matcher / call-graph machinery never drop a statically-
 * required edge. A wrong LLM answer costs at most precision.
 *
 * <p>Activated by option {@code reflection-inference:llm}. The oracle is injected
 * via {@link #setOracle} (tests/mock) or built live from {@link pta.llm.ApiKeyResolver}.
 */
public class LlmInferenceModel extends InferenceModel {

    private static final Logger logger = LoggerFactory.getLogger(LlmInferenceModel.class);

    /** Max IR statements of the enclosing method included as prompt context. */
    private static final int CONTEXT_STMTS = 40;

    private static volatile LlmOracle oracleOverride;

    public static void setOracle(LlmOracle oracle) {
        oracleOverride = oracle;
    }

    public static void clearOracle() {
        oracleOverride = null;
    }

    /** Returns whether a static oracle override is installed (used by the eval harness). */
    public static boolean hasOracle() {
        return oracleOverride != null;
    }

    /**
     * SOLAR-N3 self-flagging: residual sites the LLM was queried for but could not
     * resolve to any loaded target this run (never silently dropped). Cleared per
     * analysis (the model is reconstructed in {@code ReflectionAnalysis.setSolver}).
     */
    private static final Set<String> GAP =
            java.util.Collections.synchronizedSet(Sets.newLinkedSet());

    /**
     * Sites whose subtype expansion overflowed κ (G1): they DID resolve targets but the
     * expansion is knowingly incomplete, so the flag must survive {@link #markResolution}
     * — kept separate from the unresolved ledger.
     */
    private static final Set<String> OVERFLOW =
            java.util.Collections.synchronizedSet(Sets.newLinkedSet());

    /** Read-only snapshot of the residual gap report (unresolved ∪ overflow). */
    public static java.util.List<String> gapReport() {
        java.util.List<String> out = new ArrayList<>();
        synchronized (GAP) {
            out.addAll(GAP);
        }
        synchronized (OVERFLOW) {
            OVERFLOW.stream().filter(s -> !out.contains(s)).forEach(out::add);
        }
        return java.util.List.copyOf(out);
    }

    private final LlmOracle oracle;

    /** Sites already queried (dedup the LLM call across pts-change re-fires). */
    private final Set<Invoke> queried = Sets.newSet();

    /**
     * Sites whose name var has been OBSERVED to carry an Unknown (non-constant) string
     * in some fire — the latched residual predicate ⊤ ∈ Ŝ(n). Latching makes the
     * trigger monotone: a co-present constant in the same (or another) delta batch can
     * never suppress the residual, and an empty/transient pts never burns the query.
     */
    private final Set<Invoke> unknownNames = Sets.newSet();

    /**
     * Cached LLM proposals per queried site. Handlers re-fire with the DELTA of the
     * changed var (and full pts of the others), so classes arriving after the one-shot
     * query must have the cached proposals re-injected — injection is per-fire,
     * querying is once.
     */
    private final java.util.Map<Invoke, List<String>> proposals =
            pascal.taie.util.collection.Maps.newMap();

    /** Sites that resolved ≥1 loaded target in ANY fire (cumulative, monotone). */
    private final Set<Invoke> resolvedSites = Sets.newSet();

    /** Name-bearing reflective sites seen (for the empty-points-to trigger). */
    private final Set<Invoke> reflSites = Sets.newSet();

    /** Sites whose empty-pts name var has been seeded with a placeholder (dedup). */
    private final Set<Invoke> placeholderDone = Sets.newSet();

    private static final Descriptor NAME_PH = () -> "LlmUnknownReflName";

    // -----------------------------------------------------------------------
    // B-wave staged querying: during solving, residual sites are only LATCHED
    // (deferred); queries fire at onPhaseFinish against the CONVERGED phase
    // state — the lfp of the constraints-so-far, unique regardless of worklist
    // order — so prompts are canonical and the analysis is deterministic by
    // induction over phases. Legacy fire-once behavior stays under
    // -Darm2.staged=false for before/after measurement.
    // -----------------------------------------------------------------------

    /** Staged mode flag: default TRUE; {@code -Darm2.staged=false} restores fire-once. */
    private final boolean staged =
            !"false".equals(System.getProperty("arm2.staged", "true"));

    /** A residual site deferred to the phase boundary (staged mode). */
    private record Deferred(Context context, Invoke invoke, String kind, String question) {
    }

    /** Deferred residual sites; entries stay across phases (evidence may mature). */
    private final Set<Deferred> pending = Sets.newLinkedSet();

    /** Per-invoke prompt-version latch: SHA-256 of every prompt version queried. */
    private final java.util.Map<Invoke, Set<String>> promptVersions =
            pascal.taie.util.collection.Maps.newMap();

    /** Cap on distinct prompt versions queried per site (staged mode). */
    private static final int MAX_PROMPT_VERSIONS = 4;

    /** Per-deferral ledger of already-injected proposals (keeps the phase loop terminating). */
    private final java.util.Map<Deferred, Set<String>> injectedByEntry =
            pascal.taie.util.collection.Maps.newMap();

    private static final String Q_CLASS =
            "A reflective Class.forName(...) has a non-constant class name. "
                    + "Given the surrounding code, list the fully-qualified names of "
                    + "the classes it may load, one per line.";

    private static final String Q_METHOD =
            "A reflective getMethod(...) has a non-constant method name. "
                    + "Given the surrounding code, list the method name(s) it may "
                    + "retrieve, one per line.";

    private static final String Q_FIELD =
            "A reflective getField(...) has a non-constant field name. "
                    + "Given the surrounding code, list the field name(s) it may "
                    + "retrieve, one per line.";

    LlmInferenceModel(Solver solver, MetaObjHelper helper, Set<Invoke> invokesWithLog) {
        super(solver, helper, invokesWithLog);
        this.oracle = pta.llm.CorruptingOracle.wrapIfConfigured(resolveOracle(),
                () -> World.get().getClassHierarchy().applicationClasses()
                        .map(JClass::getName).sorted().toList());
        resetLedgers();
    }

    /**
     * Clears the static SOLAR-N3 ledgers. Called from every
     * {@code ReflectionAnalysis.setSolver} (any mode), so a run never reports a previous
     * run's residuals in the same JVM.
     */
    public static void resetLedgers() {
        GAP.clear();
        OVERFLOW.clear();
        QUERIES.set(0);
        LIVE_QUERIES.set(0);
        PROPOSED.set(0);
        PHI_REJECT.set(0);
        INJECTED.set(0);
        synchronized (COST) {
            COST[0] = 0.0;
        }
    }

    /** Experiment ablation switch: {@code -Darm2.ablate.<component>} disables it. */
    private static boolean ablated(String component) {
        return System.getProperty("arm2.ablate." + component) != null;
    }

    // ---- oracle-usage accounting for the cost report (reset per analysis) ----
    private static final java.util.concurrent.atomic.AtomicInteger QUERIES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LIVE_QUERIES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final double[] COST = {0.0};

    // ---- failure-mode accounting: how the sound disposer treats LLM proposals ----
    /** class-name proposals received from the oracle (forName sites). */
    private static final java.util.concurrent.atomic.AtomicInteger PROPOSED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** proposals dropped by Phi realizability (name loads no class on the classpath). */
    private static final java.util.concurrent.atomic.AtomicInteger PHI_REJECT =
            new java.util.concurrent.atomic.AtomicInteger();
    /** proposals that resolved to a loaded class and were injected. */
    private static final java.util.concurrent.atomic.AtomicInteger INJECTED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** {@code queries,liveQueries,costUsd} of the current/last analysis run. */
    public static String oracleStats() {
        synchronized (COST) {
            return QUERIES.get() + "," + LIVE_QUERIES.get() + "," + COST[0];
        }
    }

    /** {@code proposed,phiRejected,injected} class-name proposals (failure-mode RQ). */
    public static String disposerStats() {
        return PROPOSED.get() + "," + PHI_REJECT.get() + "," + INJECTED.get();
    }

    /** Canonical site id used for the gap report and prompts. */
    private static String siteId(Invoke invoke) {
        return invoke.getContainer().getSignature() + "@" + invoke.getIndex();
    }

    /**
     * SOLAR-N3 ledger update, cumulative across fires: once any fire resolves a loaded
     * target the site stays resolved (and is un-flagged); it is flagged only while no
     * fire has resolved anything.
     */
    private void markResolution(Invoke invoke, boolean resolvedAnyThisFire) {
        if (resolvedAnyThisFire) {
            resolvedSites.add(invoke);
            GAP.remove(siteId(invoke));
        } else if (!resolvedSites.contains(invoke)) {
            GAP.add(siteId(invoke));
        }
    }

    private static LlmOracle resolveOracle() {
        if (oracleOverride != null) {
            return oracleOverride;
        }
        String key = pta.llm.ApiKeyResolver.resolve();
        if (key.isEmpty()) {
            return null; // no key, no override → resolves only constants (sound no-op for LLM)
        }
        String model = System.getProperty("arm2.model", "gemini-2.5-flash");
        return new pta.llm.GeminiOracle(model, key,
                new pta.llm.PromptCache(java.nio.file.Path.of(".llm-cache")),
                new pta.llm.CostMeter(100.0, 3e-7, 2.5e-6));
    }

    // -----------------------------------------------------------------------
    // forName / loadClass — resolve the (possibly Unknown) class name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.Class forName(java.lang.String)>",
            "<java.lang.Class: java.lang.Class forName(java.lang.String,boolean,java.lang.ClassLoader)>",
            "<java.lang.ClassLoader: java.lang.Class loadClass(java.lang.String)>"},
            argIndexes = {0})
    public void classForName(Context context, Invoke invoke, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        // Resolve every constant, and note whether ANY name obj is Unknown (a merged /
        // non-constant string). A co-present bogus constant (e.g. DaCapo's findClass,
        // where config.className points to a bag of parser constants PLUS a merged
        // Unknown) must NOT suppress the LLM for the Unknown part.
        boolean[] hasUnknown = {false};
        nameObjs.forEach(obj -> {
            String s = CSObjs.toString(obj);
            if (s != null) {
                classForNameKnown(context, invoke, s);
            } else {
                hasUnknown[0] = true;
            }
        });
        // Residual: name is (partly) input-dependent. Staged (default): only LATCH the
        // site; the query fires at onPhaseFinish against the converged state. Legacy
        // (-Darm2.staged=false): fire-once mid-flight.
        if (hasUnknown[0] && oracle != null) {
            if (staged) {
                pending.add(new Deferred(context, invoke, "llm-class", Q_CLASS));
            } else if (queried.add(invoke)) {
                boolean resolvedAny = false;
                for (String className : askLlm("llm-class", invoke, Q_CLASS)) {
                    if (injectClassAndSubtypes(context, invoke, className.trim())) {
                        resolvedAny = true;
                    }
                }
                markResolution(invoke, resolvedAny);
            }
        }
    }

    // -----------------------------------------------------------------------
    // getMethod / getDeclaredMethod — resolve the (possibly Unknown) method name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Method getMethod(java.lang.String,java.lang.Class[])>",
            "<java.lang.Class: java.lang.reflect.Method getDeclaredMethod(java.lang.String,java.lang.Class[])>"},
            argIndexes = {BASE, 0})
    public void classGetMethod(Context context, Invoke invoke,
                               PointsToSet classObjs, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        List<JClass> classes = new ArrayList<>();
        classObjs.forEach(co -> {
            JClass clazz = CSObjs.toClass(co);
            if (clazz != null) {
                classes.add(clazz);
            }
        });
        // Resolve constants and LATCH Unknown observation (a co-present constant — in
        // this or any other delta batch — must not suppress the residual; an empty or
        // transient pts must not burn the one-shot query).
        classes.forEach(clazz -> nameObjs.forEach(no -> {
            String name = CSObjs.toString(no);
            if (name != null) {
                classGetMethodKnown(context, invoke, clazz, name);
            }
        }));
        nameObjs.forEach(no -> {
            if (CSObjs.toString(no) == null) {
                unknownNames.add(invoke);
            }
        });
        // Residual (⊤ ∈ Ŝ(n), latched). Staged (default): only DEFER — the query and
        // the injection (against the classes re-derived from the then-current pts)
        // happen at onPhaseFinish. Legacy: query once, but RE-INJECT the cached
        // proposals on every fire — handlers receive the DELTA of the changed var, so
        // classes arriving after the first query would otherwise never get the proposals.
        if (staged) {
            if (unknownNames.contains(invoke) && oracle != null) {
                pending.add(new Deferred(context, invoke, "llm-method", Q_METHOD));
            }
            return;
        }
        if (unknownNames.contains(invoke) && !classes.isEmpty() && oracle != null) {
            List<String> props = proposals.get(invoke);
            if (props == null && queried.add(invoke)) {
                props = askLlm("llm-method", invoke, Q_METHOD);
                proposals.put(invoke, props);
            }
            if (props != null) {
                boolean resolvedAny = false;
                for (String name : props) {
                    for (JClass clazz : classes) {
                        if (memberExists(invoke, clazz, name.trim())) {
                            resolvedAny = true;
                        }
                        classGetMethodKnown(context, invoke, clazz, name.trim());
                    }
                }
                markResolution(invoke, resolvedAny);
            }
        }
    }

    // -----------------------------------------------------------------------
    // getField / getDeclaredField — resolve the (possibly Unknown) field name
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Field getField(java.lang.String)>",
            "<java.lang.Class: java.lang.reflect.Field getDeclaredField(java.lang.String)>"},
            argIndexes = {BASE, 0})
    public void classGetField(Context context, Invoke invoke,
                              PointsToSet classObjs, PointsToSet nameObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        List<JClass> classes = new ArrayList<>();
        classObjs.forEach(co -> {
            JClass clazz = CSObjs.toClass(co);
            if (clazz != null) {
                classes.add(clazz);
            }
        });
        // Same latched-residual + cached-proposal protocol as classGetMethod (S3/S4).
        classes.forEach(clazz -> nameObjs.forEach(no -> {
            String name = CSObjs.toString(no);
            if (name != null) {
                classGetFieldKnown(context, invoke, clazz, name);
            }
        }));
        nameObjs.forEach(no -> {
            if (CSObjs.toString(no) == null) {
                unknownNames.add(invoke);
            }
        });
        if (staged) {
            if (unknownNames.contains(invoke) && oracle != null) {
                pending.add(new Deferred(context, invoke, "llm-field", Q_FIELD));
            }
            return;
        }
        if (unknownNames.contains(invoke) && !classes.isEmpty() && oracle != null) {
            List<String> props = proposals.get(invoke);
            if (props == null && queried.add(invoke)) {
                props = askLlm("llm-field", invoke, Q_FIELD);
                proposals.put(invoke, props);
            }
            if (props != null) {
                boolean resolvedAny = false;
                for (String name : props) {
                    for (JClass clazz : classes) {
                        if (memberExists(invoke, clazz, name.trim())) {
                            resolvedAny = true;
                        }
                        classGetFieldKnown(context, invoke, clazz, name.trim());
                    }
                }
                markResolution(invoke, resolvedAny);
            }
        }
    }

    /**
     * S6: the "did a proposal resolve?" probe must use the SAME lookup variant as the
     * injection ({@code getDeclared*} searches the class only; the public variants
     * search the hierarchy) — otherwise a site can be reported resolved while injecting
     * nothing (or vice versa), corrupting the SOLAR-N3 ledger.
     */
    private static boolean memberExists(Invoke invoke, JClass clazz, String name) {
        return switch (invoke.getMethodRef().getName()) {
            case "getMethod" -> pascal.taie.language.classes.Reflections
                    .getMethods(clazz, name).findAny().isPresent();
            case "getDeclaredMethod" -> pascal.taie.language.classes.Reflections
                    .getDeclaredMethods(clazz, name).findAny().isPresent();
            case "getField" -> pascal.taie.language.classes.Reflections
                    .getFields(clazz, name).findAny().isPresent();
            case "getDeclaredField" -> pascal.taie.language.classes.Reflections
                    .getDeclaredFields(clazz, name).findAny().isPresent();
            default -> false;
        };
    }

    // -----------------------------------------------------------------------
    // getConstructor — no name to resolve; mirror StringBasedModel for known class
    // -----------------------------------------------------------------------

    @InvokeHandler(signature = {
            "<java.lang.Class: java.lang.reflect.Constructor getConstructor(java.lang.Class[])>",
            "<java.lang.Class: java.lang.reflect.Constructor getDeclaredConstructor(java.lang.Class[])>"},
            argIndexes = {BASE})
    public void classGetConstructor(Context context, Invoke invoke, PointsToSet classObjs) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        classObjs.forEach(co -> classGetConstructorKnown(context, invoke, CSObjs.toClass(co)));
    }

    // -----------------------------------------------------------------------
    // Empty-points-to trigger: a reflective name built by string ops or read from
    // config often leaves its name var with an EMPTY points-to set, so the
    // pts-driven handler never fires. Seed such name vars with a placeholder
    // String so the handler fires and resolves them via the LLM + the extracted
    // string-flow context. The placeholder is not a string constant, so it is
    // treated as Unknown — sound (only ever adds candidates).
    // -----------------------------------------------------------------------

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        super.onNewStmt(stmt, container);
        if (oracle != null && stmt instanceof Invoke invoke
                && !invoke.isDynamic() && isNameBearingReflection(invoke.getMethodRef())) {
            reflSites.add(invoke);
        }
    }

    @Override
    public void onPhaseFinish() {
        if (oracle == null) {
            return;
        }
        if (!ablated("seeding")) {
            seedPlaceholders();
        }
        if (staged) {
            processDeferred();
        }
    }

    private void seedPlaceholders() {
        // One pass over the reachable set per phase, indexed by the containers we
        // actually care about — not a full-materialize-then-filter per site (E1).
        Set<JMethod> unseeded = Sets.newSet();
        for (Invoke site : reflSites) {
            if (!placeholderDone.contains(site)) {
                unseeded.add(site.getContainer());
            }
        }
        if (unseeded.isEmpty()) {
            return;
        }
        java.util.Map<JMethod, List<Context>> ctxIndex =
                pascal.taie.util.collection.Maps.newMap();
        solver.getCallGraph().reachableMethods().forEach(m -> {
            if (unseeded.contains(m.getMethod())) {
                ctxIndex.computeIfAbsent(m.getMethod(), k -> new ArrayList<>())
                        .add(m.getContext());
            }
        });
        for (Invoke site : reflSites) {
            if (!placeholderDone.contains(site)) {
                seedPlaceholderIfEmptyPts(site,
                        ctxIndex.getOrDefault(site.getContainer(), List.of()));
            }
        }
    }

    /**
     * B-wave staged querying: at the phase boundary the points-to state is the
     * converged lfp of the constraints-so-far — unique regardless of worklist
     * order — so prompts built here are CANONICAL and the analysis result is
     * schedule-independent by induction over phases. Injections seed the next
     * phase; the loop terminates once a phase queries and injects nothing new.
     */
    private void processDeferred() {
        List<Deferred> entries = new ArrayList<>(pending);
        // canonical processing order (site, kind, context) — the query/injection
        // sequence itself must not depend on worklist-discovery order
        entries.sort(java.util.Comparator
                .comparing((Deferred d) -> siteId(d.invoke()))
                .thenComparing(Deferred::kind)
                .thenComparing(d -> d.context().toString()));
        for (Deferred d : entries) {
            // Re-check the residual predicate against the CURRENT (converged) pts of
            // the name var: query only sites that are still residual at the boundary.
            if (!hasUnknownName(d)) {
                continue;
            }
            List<JClass> classes = List.of();
            if (!"llm-class".equals(d.kind())) {
                classes = currentClasses(d);
                if (classes.isEmpty()) {
                    continue; // member site without receiver classes yet; retry next phase
                }
            }
            String prompt = buildPrompt(d.kind(), d.invoke(), d.question());
            Set<String> versions = promptVersions.computeIfAbsent(
                    d.invoke(), k -> Sets.newLinkedSet());
            String hash = sha256(prompt);
            if (!versions.contains(hash)) {
                if (versions.size() >= MAX_PROMPT_VERSIONS) {
                    // Mirror the G1 overflow protocol: never silently drop — flag the
                    // site as an under-approximated residual in the gap report.
                    OVERFLOW.add(siteId(d.invoke()));
                    logger.warn("[arm2-llm] prompt-version cap κ={} exceeded at {}; "
                            + "further evidence maturation is flagged as an "
                            + "under-approximated residual (not silently dropped)",
                            MAX_PROMPT_VERSIONS, siteId(d.invoke()));
                } else {
                    versions.add(hash);
                    List<String> merged = proposals.computeIfAbsent(
                            d.invoke(), k -> new ArrayList<>());
                    for (String line : queryOracle(d.kind(), prompt, siteId(d.invoke()))) {
                        String t = line.trim();
                        if (!t.isEmpty() && !merged.contains(t)) {
                            merged.add(t);
                        }
                    }
                }
            }
            List<String> props = proposals.get(d.invoke());
            if (props == null || props.isEmpty()) {
                if (!versions.isEmpty()) {
                    markResolution(d.invoke(), false); // queried, nothing usable → gap
                }
                continue;
            }
            injectProposals(d, props, classes);
        }
    }

    /** Whether the deferral's name var still carries an Unknown in its CURRENT pts. */
    private boolean hasUnknownName(Deferred d) {
        Var nameVar = pascal.taie.analysis.pta.plugin.util.InvokeUtils
                .getVar(d.invoke(), 0);
        PointsToSet pts = solver.getPointsToSetOf(
                solver.getCSManager().getCSVar(d.context(), nameVar));
        for (var obj : pts) {
            if (CSObjs.toString(obj) == null) {
                return true;
            }
        }
        return false;
    }

    /** Receiver classes of a member site, re-derived from the CURRENT (converged) pts. */
    private List<JClass> currentClasses(Deferred d) {
        Var baseVar = pascal.taie.analysis.pta.plugin.util.InvokeUtils
                .getVar(d.invoke(), BASE);
        PointsToSet pts = solver.getPointsToSetOf(
                solver.getCSManager().getCSVar(d.context(), baseVar));
        List<JClass> classes = new ArrayList<>();
        pts.forEach(co -> {
            JClass clazz = CSObjs.toClass(co);
            if (clazz != null && !classes.contains(clazz)) {
                classes.add(clazz);
            }
        });
        return classes;
    }

    /**
     * Injects the (cumulative) proposals of a deferred site, skipping what this
     * deferral already injected — so a phase that adds nothing new schedules no
     * work and the outer phase loop terminates.
     */
    private void injectProposals(Deferred d, List<String> props, List<JClass> classes) {
        Set<String> done = injectedByEntry.computeIfAbsent(d, k -> Sets.newLinkedSet());
        boolean attempted = false;
        boolean resolvedAny = false;
        if ("llm-class".equals(d.kind())) {
            for (String name : props) {
                if (done.add(name)) {
                    attempted = true;
                    if (injectClassAndSubtypes(d.context(), d.invoke(), name)) {
                        resolvedAny = true;
                    }
                }
            }
        } else {
            boolean isMethod = "llm-method".equals(d.kind());
            for (String name : props) {
                for (JClass clazz : classes) {
                    if (done.add(clazz.getName() + "#" + name)) {
                        attempted = true;
                        if (memberExists(d.invoke(), clazz, name)) {
                            resolvedAny = true;
                        }
                        if (isMethod) {
                            classGetMethodKnown(d.context(), d.invoke(), clazz, name);
                        } else {
                            classGetFieldKnown(d.context(), d.invoke(), clazz, name);
                        }
                    }
                }
            }
        }
        if (attempted) {
            markResolution(d.invoke(), resolvedAny);
        }
    }

    private static String sha256(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private void seedPlaceholderIfEmptyPts(Invoke site, List<Context> ctxs) {
        Var nameVar = site.getInvokeExp().getArg(0);
        JMethod container = site.getContainer();
        if (ctxs.isEmpty()) {
            return; // container not reachable yet; retry next phase
        }
        boolean anyPts = ctxs.stream().anyMatch(c ->
                !solver.getPointsToSetOf(solver.getCSManager().getCSVar(c, nameVar)).isEmpty());
        if (anyPts) {
            placeholderDone.add(site); // pts-driven handler already fires here
            return;
        }
        Type strType = solver.getTypeSystem().stringType();
        Obj ph = solver.getHeapModel().getMockObj(NAME_PH,
                "unknown-name@" + container.getSignature() + "#" + site.getIndex(),
                strType, container);
        for (Context c : ctxs) {
            solver.addVarPointsTo(c, nameVar, solver.getCSManager().getCSObj(c, ph));
        }
        placeholderDone.add(site);
    }

    private static boolean isNameBearingReflection(MethodRef ref) {
        String dc = ref.getDeclaringClass().getName();
        String mn = ref.getName();
        return switch (dc) {
            case "java.lang.Class" -> mn.equals("forName")
                    || mn.equals("getMethod") || mn.equals("getDeclaredMethod")
                    || mn.equals("getField") || mn.equals("getDeclaredField");
            case "java.lang.ClassLoader" -> mn.equals("loadClass");
            default -> false;
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Cap on concrete subclasses injected when a target is abstract/interface. */
    private static final int MAX_SUBTYPES = 64;

    /**
     * Injects the resolved class, and — because {@code forName(...).newInstance()} cannot
     * instantiate an abstract class or interface — its concrete subclasses too (the real
     * runtime object is a subtype). Returns whether the name resolved to a known class.
     */
    private boolean injectClassAndSubtypes(Context context, Invoke invoke, String name) {
        PROPOSED.incrementAndGet();
        classForNameKnown(context, invoke, name);
        JClass c = hierarchy.getClass(name);
        if (c == null) {
            PHI_REJECT.incrementAndGet(); // Phi: proposed name loads no class (hallucination)
            return false;
        }
        INJECTED.incrementAndGet();
        if ((c.isAbstract() || c.isInterface()) && !ablated("subtypeExpansion")) {
            int cap = Integer.getInteger("arm2.maxSubtypes", MAX_SUBTYPES);
            int n = 0;
            for (JClass sub : hierarchy.getAllSubclassesOf(c)) {
                if (sub != c && !sub.isAbstract() && !sub.isInterface()) {
                    classForNameKnown(context, invoke, sub.getName());
                    if (++n >= cap) {
                        // G1: more concrete subtypes than the precision-effort bound κ.
                        // SILENTLY truncating here can drop a true runtime target (unsound).
                        // The formal ideal is to widen to OverApprox(B); but injecting all
                        // subtypes of a broad type would blow up, so the PRACTICAL sound
                        // choice (and SOLAR's own behaviour for high-target calls) is to
                        // FLAG the site as an under-approximated residual — explicit, not
                        // silent, and bounded.
                        OVERFLOW.add(siteId(invoke));
                        logger.info("[arm2-llm] subtype expansion of {} exceeded κ={} at {}; "
                                + "flagged as under-approximated residual (not silently "
                                + "truncated)", name, cap, siteId(invoke));
                        break;
                    }
                }
            }
        }
        return true;
    }

    /** Legacy fire-once path: build the prompt at fire time and query immediately. */
    private List<String> askLlm(String kind, Invoke invoke, String question) {
        return queryOracle(kind, buildPrompt(kind, invoke, question), siteId(invoke));
    }

    /**
     * Builds the prompt for a residual site. In staged mode this runs at the phase
     * boundary, so every input (IR-derived evidence, config values, grounding hints)
     * is a function of the converged state — the prompt is canonical.
     */
    private String buildPrompt(String kind, Invoke invoke, String question) {
        String siteId = siteId(invoke);
        // Objectively model the obtainable name evidence (string-flow / config) and
        // quality-gate it: HIGH-quality fragments are fed directly; LOW-quality is
        // preprocessed into a best-effort summary (see ReflectionContextExtractor).
        Var nameVar = invoke.getInvokeExp().getArg(0);
        ReflectionContextExtractor.Context ctx =
                ReflectionContextExtractor.extract(nameVar, invoke.getContainer());
        StringBuilder prompt = new StringBuilder();
        // Application identity: an out-of-band fact the analysis knows (e.g. which
        // benchmark/app is under analysis) but the method body does not. It lets the
        // LLM propose convention-driven names (e.g. the harness class from the app id)
        // that are unrecoverable from the code alone.
        String appContext = System.getProperty("arm2.appContext");
        if (appContext != null && !appContext.isBlank()) {
            prompt.append("Application under analysis: ").append(appContext).append('\n');
        }
        prompt.append(question)
                .append("\nSite: ").append(siteId).append('\n').append(ctx.promptText());
        // HIGH-quality config path: if the name is config-driven, read the actual
        // values from the .properties on the classpath and feed them directly.
        if (ctx.fromConfig() || ctx.fromResource()) {
            List<String> values = ConfigResolver.valuesForKeys(ctx.fragments());
            if (!values.isEmpty()) {
                prompt.append("Config values found on the classpath for these keys "
                        + "(high-confidence candidates): ").append(values).append('\n');
            }
        }
        // Ground the proposal in real classpath classes: list application classes whose
        // name matches the app id, so the LLM chooses an existing class instead of
        // hallucinating a plausible-but-absent name (which the sound gate would drop).
        String classHint = System.getProperty("arm2.classHint");
        if (!ablated("grounding")
                && "llm-class".equals(kind) && classHint != null && !classHint.isBlank()) {
            String h = classHint.toLowerCase();
            // Determinism (B-wave part 2): applicationClasses() streams the hierarchy's
            // class list in RESOLUTION order, which varies across JVM runs — taking the
            // first 40 of an unordered stream yields run-dependent prompt bytes (the
            // observed same-config prompt variance). Sort BEFORE limit: the candidate
            // slot is canonically the alphabetically first 40 matches.
            List<String> candidates = World.get().getClassHierarchy().applicationClasses()
                    .map(JClass::getName)
                    .filter(n -> n.toLowerCase().contains(h))
                    .distinct().sorted().limit(40).toList();
            if (!candidates.isEmpty()) {
                prompt.append("Classes on the classpath matching the application id "
                        + "(choose the exact one): ").append(candidates).append('\n');
            }
        }
        prompt.append("Enclosing method body:\n").append(body(invoke));
        return prompt.toString();
    }

    /** Sends one prompt to the oracle; accounting + robustness shared by both modes. */
    private List<String> queryOracle(String kind, String prompt, String siteId) {
        try {
            pta.llm.LlmResponse resp = oracle.ask(new LlmQuery(kind, prompt, siteId));
            QUERIES.incrementAndGet();
            if (!resp.fromCache()) {
                LIVE_QUERIES.incrementAndGet();
                synchronized (COST) {
                    COST[0] += resp.estCostUsd();
                }
            }
            List<String> lines = resp.asLines();
            logger.info("[arm2-llm] {} at {} → {}", kind, siteId, lines);
            return lines;
        } catch (RuntimeException e) {
            logger.warn("[arm2-llm] query failed for {}: {}", siteId, e.getMessage());
            return List.of();
        }
    }

    private static String body(Invoke invoke) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Stmt s : invoke.getContainer().getIR().getStmts()) {
            sb.append("  ").append(s).append('\n');
            if (++n >= CONTEXT_STMTS) {
                sb.append("  ...\n");
                break;
            }
        }
        return sb.toString();
    }
}
