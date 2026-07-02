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

    LlmInferenceModel(Solver solver, MetaObjHelper helper, Set<Invoke> invokesWithLog) {
        super(solver, helper, invokesWithLog);
        this.oracle = resolveOracle();
        GAP.clear();
        OVERFLOW.clear();
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
        return new pta.llm.GeminiOracle("gemini-2.5-flash", key,
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
        // Residual: name is (partly) input-dependent → ask the LLM, inject its proposals.
        if (hasUnknown[0] && oracle != null && queried.add(invoke)) {
            boolean resolvedAny = false;
            for (String className : askLlm("llm-class", invoke,
                    "A reflective Class.forName(...) has a non-constant class name. "
                            + "Given the surrounding code, list the fully-qualified names of "
                            + "the classes it may load, one per line.")) {
                if (injectClassAndSubtypes(context, invoke, className.trim())) {
                    resolvedAny = true;
                }
            }
            markResolution(invoke, resolvedAny);
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
        // Residual (⊤ ∈ Ŝ(n), latched): query once, but RE-INJECT the cached proposals
        // on every fire — handlers receive the DELTA of the changed var, so classes
        // arriving after the first query would otherwise never get the proposals.
        if (unknownNames.contains(invoke) && !classes.isEmpty() && oracle != null) {
            List<String> props = proposals.get(invoke);
            if (props == null && queried.add(invoke)) {
                props = askLlm("llm-method", invoke,
                        "A reflective getMethod(...) has a non-constant method name. "
                                + "Given the surrounding code, list the method name(s) it may "
                                + "retrieve, one per line.");
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
        if (unknownNames.contains(invoke) && !classes.isEmpty() && oracle != null) {
            List<String> props = proposals.get(invoke);
            if (props == null && queried.add(invoke)) {
                props = askLlm("llm-field", invoke,
                        "A reflective getField(...) has a non-constant field name. "
                                + "Given the surrounding code, list the field name(s) it may "
                                + "retrieve, one per line.");
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
        List<CSMethod> reachable = solver.getCallGraph().reachableMethods().toList();
        for (Invoke site : reflSites) {
            if (!placeholderDone.contains(site)) {
                seedPlaceholderIfEmptyPts(site, reachable);
            }
        }
    }

    private void seedPlaceholderIfEmptyPts(Invoke site, List<CSMethod> reachable) {
        Var nameVar = site.getInvokeExp().getArg(0);
        JMethod container = site.getContainer();
        List<Context> ctxs = reachable.stream()
                .filter(m -> m.getMethod().equals(container))
                .map(CSMethod::getContext)
                .toList();
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
        classForNameKnown(context, invoke, name);
        JClass c = hierarchy.getClass(name);
        if (c == null) {
            return false;
        }
        if (c.isAbstract() || c.isInterface()) {
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

    private List<String> askLlm(String kind, Invoke invoke, String question) {
        String siteId = invoke.getContainer().getSignature() + "@" + invoke.getIndex();
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
        if ("llm-class".equals(kind) && classHint != null && !classHint.isBlank()) {
            String h = classHint.toLowerCase();
            List<String> candidates = World.get().getClassHierarchy().applicationClasses()
                    .map(JClass::getName)
                    .filter(n -> n.toLowerCase().contains(h))
                    .distinct().limit(40).toList();
            if (!candidates.isEmpty()) {
                prompt.append("Classes on the classpath matching the application id "
                        + "(choose the exact one): ").append(candidates).append('\n');
            }
        }
        prompt.append("Enclosing method body:\n").append(body(invoke));
        try {
            List<String> lines = oracle.ask(new LlmQuery(kind, prompt.toString(), siteId)).asLines();
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
