package pta.arm2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.TypeSystem;
import pta.llm.LlmOracle;
import pta.llm.LlmQuery;
import pta.llm.LlmResponse;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Arm 2: LLM-augmented reflection / indirect-call target resolution.
 * <p>
 * At reflective sites that Tai-e's base inference leaves empty (e.g.
 * {@code Class.forName(nonConstantString).newInstance()}, where the class name
 * is not a string constant), this plugin asks an {@link LlmOracle} for
 * candidate target classes, runs the proposals through the sound
 * {@link TargetFilter}, and adds a call edge to each surviving constructor via
 * {@link Solver#addCallEdge}.
 * <p>
 * <b>Soundness.</b> Tai-e exposes no API to remove a call edge, so this arm
 * only ever <em>adds</em> targets, and only type-valid ones (the filter). A
 * wrong LLM answer can at most add a type-valid-but-irrelevant edge (a
 * precision cost); it can never drop a real edge. Edges are therefore
 * monotonically recall-improving.
 * <p>
 * <b>Activation.</b> The plugin is option-gated by registration: it runs only
 * when listed in the {@code pta} option {@code plugins:[pta.arm2.LlmReflectionModel]}.
 * It is instantiated by Tai-e via its no-argument constructor; the oracle is
 * supplied through {@link #setOracle} (used by the offline tests with
 * {@code MockOracle}). Without an injected oracle it is a no-op.
 */
public class LlmReflectionModel implements Plugin {

    private static final Logger logger =
            LoggerFactory.getLogger(LlmReflectionModel.class);

    private static final Descriptor LLM_REF_OBJ = () -> "LlmReflectiveObj";

    /**
     * No network in tests: the oracle is injected here. {@code null} means the
     * plugin performs no LLM queries (no-op), keeping the analysis offline-safe.
     */
    private static LlmOracle oracleOverride;

    public static void setOracle(LlmOracle oracle) {
        oracleOverride = oracle;
    }

    public static void clearOracle() {
        oracleOverride = null;
    }

    private Solver solver;

    private CSManager csManager;

    private HeapModel heapModel;

    private ContextSelector selector;

    private ClassHierarchy hierarchy;

    private TypeSystem typeSystem;

    private LlmOracle oracle;

    private final TargetFilter filter = new TargetFilter();

    /**
     * Reflective {@code Class.newInstance()} call sites seen during analysis.
     */
    private final Set<Invoke> candidates = new LinkedHashSet<>();

    /**
     * Context-sensitive call sites already processed (dedup / termination).
     */
    private final Set<CSCallSite> handled = new HashSet<>();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
        this.heapModel = solver.getHeapModel();
        this.selector = solver.getContextSelector();
        this.hierarchy = solver.getHierarchy();
        this.typeSystem = solver.getTypeSystem();
        this.oracle = oracleOverride;
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        if (stmt instanceof Invoke invoke && !invoke.isDynamic()) {
            MethodRef ref = invoke.getMethodRef();
            if ("java.lang.Class".equals(ref.getDeclaringClass().getName())
                    && "newInstance".equals(ref.getName())
                    && ref.getParameterTypes().isEmpty()) {
                candidates.add(invoke);
            }
        }
    }

    @Override
    public void onPhaseFinish() {
        if (oracle == null || candidates.isEmpty()) {
            return;
        }
        // Snapshot reachable methods: addCallEdge below mutates the work list.
        List<CSMethod> reachable = solver.getCallGraph().reachableMethods().toList();
        for (CSMethod csMethod : reachable) {
            JMethod method = csMethod.getMethod();
            Context context = csMethod.getContext();
            for (Invoke invoke : candidates) {
                if (invoke.getContainer() != method) {
                    continue;
                }
                CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
                if (!handled.add(csCallSite)) {
                    continue;
                }
                // Recall-only: supplement just the sites the base analysis missed.
                if (!solver.getCallGraph().getCalleesOf(csCallSite).isEmpty()) {
                    continue;
                }
                resolveWithLlm(context, invoke, csCallSite);
            }
        }
    }

    private void resolveWithLlm(Context context, Invoke invoke,
                                CSCallSite csCallSite) {
        String siteId = invoke.getContainer().getSignature() + "@" + invoke.getIndex();
        String prompt = "A reflective Class.newInstance() call at " + siteId
                + " has an unresolved target. Given the surrounding code, list the"
                + " fully-qualified names of the classes that may be instantiated"
                + " here, one per line.";
        LlmResponse response;
        try {
            response = oracle.ask(new LlmQuery("reflect-targets", prompt, siteId));
        } catch (RuntimeException e) {
            logger.warn("[arm2] LLM query failed for {}: {}", siteId, e.getMessage());
            return;
        }
        List<String> proposed = response.asLines();
        if (proposed.isEmpty()) {
            return;
        }
        // Soundness gate: only loaded, type-compatible constructors survive.
        Set<JMethod> admitted = filter.admit(invoke, proposed, hierarchy, typeSystem);
        for (JMethod init : admitted) {
            addReflectiveInitEdge(context, invoke, csCallSite, init);
        }
    }

    /**
     * Mirrors {@code ReflectiveActionModel.addReflectiveCallEdge} for the
     * no-arg-constructor case: creates a reflective object of the target type,
     * points the call result to it, passes it to {@code this}, and adds the
     * call edge. Uses {@link CallKind#OTHER} (as reflective edges do).
     */
    private void addReflectiveInitEdge(Context context, Invoke invoke,
                                       CSCallSite csCallSite, JMethod init) {
        JClass clazz = init.getDeclaringClass();
        solver.initializeClass(clazz);
        Obj newObj = heapModel.getMockObj(LLM_REF_OBJ,
                invoke.toString() + "/" + clazz.getName(),
                clazz.getType(), invoke.getContainer());
        CSObj csNewObj = csManager.getCSObj(context, newObj);
        Var result = invoke.getResult();
        if (result != null) {
            solver.addVarPointsTo(context, result, csNewObj);
        }
        Context calleeCtx = selector.selectContext(csCallSite, csNewObj, init);
        solver.addVarPointsTo(calleeCtx, init.getIR().getThis(), csNewObj);
        CSMethod csCallee = csManager.getCSMethod(calleeCtx, init);
        Edge<CSCallSite, CSMethod> edge =
                new Edge<>(CallKind.OTHER, csCallSite, csCallee);
        solver.addCallEdge(edge);
        logger.info("[arm2] LLM-recovered reflective edge: {} -> {}",
                invoke, init.getSignature());
    }
}
