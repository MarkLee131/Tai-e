package pta.arm2;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeDynamic;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.NewInstance;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Task ζ0 (M1 admission-ladder plan): cone-closure feasibility measurement.
 * GATES β1's O-disp investment — measures whether the α1 O-disp/cone-closure
 * repair (Check refuses any family whose open-receiver dispatch cones exit its
 * scope) leaves the verifier-rung instance usable on realistic library slices.
 *
 * <p>Run with {@code ./gradlew zeta0} (gated on {@code -Dzeta0.probe=true}).
 * Requires {@code java-benchmarks} (xalan/pmd jars + JRE 1.6).
 *
 * <h2>Protocol (plan §Task ζ0, implemented exactly)</h2>
 * <ol>
 * <li>Slices: xalan JAXP chain (roots = javax.xml.parsers.FactoryFinder +
 *     javax.xml.transform.FactoryFinder newInstance methods + all org.apache.*
 *     ObjectFactory methods on the classpath); pmd RuleSetFactory slice
 *     (roots = net.sourceforge.pmd.RuleSetFactory methods).</li>
 * <li>Per body in the growing closure: enumerate virtual/interface invokes;
 *     receiver TRACKED iff its base var's unique intraprocedural def (through
 *     Copy/Cast chains) is a New of a concrete class AND no chain var is passed
 *     as an argument to any call at a smaller stmt index than the dispatch
 *     (escape-havoc, syntactic approximation); else OPEN.</li>
 * <li>Per open call: CHA cone = instantiable (non-abstract, non-interface,
 *     non-phantom) hierarchy classes ≼ the receiver var's static type; also
 *     the resolved-target count.</li>
 * <li>Transitive closure from roots: tracked → the NEW class's dispatch
 *     target; open → all cone dispatch targets; static/special → resolved
 *     target. Refusal classes per closure method: native, absent body,
 *     exotic invokedynamic (bootstrap ∉ {LambdaMetafactory, StringConcatFactory}),
 *     MethodHandle/VarHandle/defineClass usage.</li>
 * <li>Configs: WITHOUT and WITH the egalitarian carve-out (Object-protocol
 *     subsignatures, receiver static type java.lang.Object, or egalitarian
 *     method names declared on java.lang / java.util interfaces — carved
 *     calls' cones do NOT enter closure; modeled).</li>
 * </ol>
 *
 * <p>Known approximations (probe-level, all blessed by the plan's "syntactic
 * origin approximation acceptable"): "unique reaching def" is implemented as
 * "unique def in the whole body"; "before the dispatch" is by statement index
 * (ignores loops); invokedynamic call edges are not expanded (indy is refusal-
 * classified instead); native-model synthetic IR is not scanned (native is a
 * refusal class).
 */
public class ConeClosureFeasibilityProbe {

    private static final String BENCH_DIR = "java-benchmarks/dacapo-2006/";

    private static final String CSV_OUT = "eval/arm2/raw/cone-feasibility.csv";

    // ==================== receiver classifier (unit-tested) ====================

    /** Classification of a virtual/interface call's receiver. */
    public record Receiver(boolean tracked, @Nullable JClass newClass) {
        public static final Receiver OPEN = new Receiver(false, null);
    }

    /**
     * Syntactic receiver classification (plan §Task ζ0 step 2).
     * TRACKED iff the base var's unique def chain (Copy/Cast transitive)
     * bottoms out at a New of a concrete class, and no var on the chain is
     * passed as an argument to any call before (by stmt index) the dispatch.
     */
    public static Receiver classifyReceiver(IR ir, Invoke invoke) {
        InvokeInstanceExp exp = (InvokeInstanceExp) invoke.getInvokeExp();
        Set<Var> chain = new LinkedHashSet<>();
        JClass newClass = chaseNewClass(ir, exp.getBase(), chain);
        if (newClass == null) {
            return Receiver.OPEN;
        }
        // escape-havoc approximation: chain var used as an ARGUMENT (not as
        // the base) of any call before the dispatch → OPEN
        for (Stmt s : ir) {
            if (s.getIndex() >= invoke.getIndex()) {
                continue;
            }
            if (s instanceof Invoke inv) {
                for (Var arg : inv.getInvokeExp().getArgs()) {
                    if (chain.contains(arg)) {
                        return Receiver.OPEN;
                    }
                }
            }
        }
        return new Receiver(true, newClass);
    }

    /**
     * Follows the unique-def chain of {@code v} through Copy/Cast to a New of
     * a concrete class; returns null (→ OPEN) on parameters/this, multiple or
     * zero defs, cycles, or any other def kind.
     */
    @Nullable
    private static JClass chaseNewClass(IR ir, Var v, Set<Var> chain) {
        while (true) {
            if (!chain.add(v)) {
                return null; // copy cycle
            }
            if (v == ir.getThis() || ir.getParams().contains(v)) {
                return null; // caller-supplied → open
            }
            Stmt def = null;
            for (Stmt s : ir) {
                if (s.getDef().isPresent() && s.getDef().get() == v) {
                    if (def != null) {
                        return null; // multiple defs → open
                    }
                    def = s;
                }
            }
            if (def == null) {
                return null;
            }
            if (def instanceof New n && n.getRValue() instanceof NewInstance ni) {
                JClass c = ni.getType().getJClass();
                return (c != null && !c.isAbstract() && !c.isInterface()) ? c : null;
            } else if (def instanceof Copy c) {
                v = c.getRValue();
            } else if (def instanceof Cast c) {
                v = c.getRValue().getValue();
            } else {
                return null; // load/call-result/phi/catch/... → open
            }
        }
    }

    // ==================== egalitarian carve-out ====================

    private static final Set<String> EGALITARIAN_NAMES = Set.of(
            "equals", "hashCode", "toString", "compareTo", "compare",
            "iterator", "next", "hasNext", "get", "put", "add", "remove",
            "size", "close", "run", "accept", "apply", "test");

    /** The java.lang.Object protocol (the plan's "Object protocol modeled"). */
    private static final Set<String> OBJECT_PROTOCOL_SUBSIGS = Set.of(
            "boolean equals(java.lang.Object)", "int hashCode()",
            "java.lang.String toString()");

    /**
     * Carve-out predicate: receiver static type is java.lang.Object, or the
     * target subsignature is the Object protocol, or the target name is an
     * egalitarian method declared on a java.lang.* / java.util.* (incl.
     * java.util.function.*) interface.
     */
    public static boolean isCarvedOut(Type staticType, MethodRef ref) {
        if (staticType instanceof ClassType ct
                && ct.getName().equals("java.lang.Object")) {
            return true;
        }
        if (OBJECT_PROTOCOL_SUBSIGS.contains(ref.getSubsignature().toString())) {
            return true;
        }
        JClass dc = ref.getDeclaringClass();
        if (EGALITARIAN_NAMES.contains(ref.getName()) && dc.isInterface()) {
            String n = dc.getName();
            return n.startsWith("java.lang.") || n.startsWith("java.util.");
        }
        return false;
    }

    // ==================== closure computation ====================

    private record OpenCall(JMethod container, int stmtIndex, String staticType,
                            String callee, int coneSize, int resolved) {
    }

    private record ClosureResult(Set<JMethod> closure, List<OpenCall> openCalls,
                                 int carvedCalls, int nativeN, int noBodyN,
                                 int exoticIndyN, int mhVhDefineClassN,
                                 int refusalN) {
    }

    /**
     * Closure expansion policy for open (non-tracked) virtual/interface calls.
     * EXPAND_ALL / CARVE_OUT are the protocol's two configs; NO_OPEN_EXPANSION
     * is an EXTRA measurement of mitigation route (b) "open-call-as-unmodeled-
     * action": open dispatches become unmodeled actions, so their cones never
     * enter the closure (only tracked + static/special edges expand).
     */
    private enum Mode {EXPAND_ALL, CARVE_OUT, NO_OPEN_EXPANSION}

    private static ClosureResult computeClosure(ClassHierarchy hier,
                                                Collection<JMethod> roots,
                                                Mode mode) {
        Set<JMethod> closure = new LinkedHashSet<>(roots);
        Deque<JMethod> wl = new ArrayDeque<>(roots);
        List<OpenCall> openCalls = new ArrayList<>();
        int carved = 0;
        while (!wl.isEmpty()) {
            JMethod m = wl.pop();
            IR ir = irOrNull(m);
            if (ir == null) {
                continue; // native/abstract/phantom: refusal-classified below
            }
            for (Stmt s : ir) {
                if (!(s instanceof Invoke inv) || inv.isDynamic()) {
                    continue; // indy edges not expanded (refusal-classified)
                }
                InvokeExp exp = inv.getInvokeExp();
                MethodRef ref = exp.getMethodRef();
                if (inv.isStatic() || inv.isSpecial()) {
                    JMethod t = ref.resolveNullable();
                    if (t != null && closure.add(t)) {
                        wl.push(t);
                    }
                    continue;
                }
                // virtual or interface dispatch
                Receiver r = classifyReceiver(ir, inv);
                if (r.tracked()) {
                    JMethod t = hier.dispatch(r.newClass(), ref);
                    if (t != null && closure.add(t)) {
                        wl.push(t);
                    }
                    continue;
                }
                Type staticType = ((InvokeInstanceExp) exp).getBase().getType();
                if (mode == Mode.CARVE_OUT && isCarvedOut(staticType, ref)) {
                    carved++;
                    continue; // modeled: cone does not enter closure
                }
                List<JClass> cone = cone(hier, staticType, ref);
                Set<JMethod> targets = new HashSet<>();
                for (JClass c : cone) {
                    JMethod t = hier.dispatch(c, ref);
                    if (t != null) {
                        targets.add(t);
                    }
                }
                openCalls.add(new OpenCall(m, inv.getIndex(), staticType.getName(),
                        ref.getDeclaringClass().getName() + "." + ref.getName(),
                        cone.size(), targets.size()));
                if (mode == Mode.NO_OPEN_EXPANSION) {
                    continue; // route (b): open call = unmodeled action
                }
                for (JMethod t : targets) {
                    if (closure.add(t)) {
                        wl.push(t);
                    }
                }
            }
        }
        // refusal classification over the final closure
        int nativeN = 0, noBodyN = 0, exoticIndyN = 0, mhVhN = 0, refusalN = 0;
        for (JMethod m : closure) {
            boolean refusal = false;
            if (m.isNative()) {
                nativeN++;
                refusal = true;
            } else if (m.isAbstract() || m.getDeclaringClass().isPhantom()
                    || irOrNull(m) == null) {
                noBodyN++;
                refusal = true;
            } else {
                boolean indy = false, mhvh = false;
                for (Stmt s : irOrNull(m)) {
                    if (!(s instanceof Invoke inv)) {
                        continue;
                    }
                    if (inv.getInvokeExp() instanceof InvokeDynamic dyn) {
                        String bsm = dyn.getBootstrapMethodRef()
                                .getDeclaringClass().getName();
                        if (!bsm.equals("java.lang.invoke.LambdaMetafactory")
                                && !bsm.equals("java.lang.invoke.StringConcatFactory")) {
                            indy = true;
                        }
                        continue;
                    }
                    MethodRef ref = inv.getInvokeExp().getMethodRef();
                    String dc = ref.getDeclaringClass().getName();
                    if (dc.equals("java.lang.invoke.MethodHandle")
                            || dc.equals("java.lang.invoke.VarHandle")
                            || ref.getName().equals("defineClass")) {
                        mhvh = true;
                    }
                }
                if (indy) {
                    exoticIndyN++;
                }
                if (mhvh) {
                    mhVhN++;
                }
                refusal = indy || mhvh;
            }
            if (refusal) {
                refusalN++;
            }
        }
        return new ClosureResult(closure, openCalls, carved,
                nativeN, noBodyN, exoticIndyN, mhVhN, refusalN);
    }

    /** CHA cone: instantiable hierarchy classes ≼ the receiver's static type. */
    private static List<JClass> cone(ClassHierarchy hier, Type staticType,
                                     MethodRef ref) {
        JClass root;
        if (staticType instanceof ClassType ct) {
            root = ct.getJClass();
        } else if (staticType instanceof ArrayType) {
            root = hier.getJREClass("java.lang.Object");
        } else {
            root = null;
        }
        if (root == null) {
            root = ref.getDeclaringClass();
        }
        return hier.getAllSubclassesOf(root).stream()
                .filter(c -> !c.isAbstract() && !c.isInterface() && !c.isPhantom())
                .toList();
    }

    @Nullable
    private static IR irOrNull(JMethod m) {
        if (m.isNative() || m.isAbstract() || m.getDeclaringClass().isPhantom()) {
            return null;
        }
        try {
            return m.getIR();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== slice roots ====================

    private static List<JMethod> collectRoots(ClassHierarchy hier, String slice) {
        List<JMethod> roots = new ArrayList<>();
        if (slice.equals("xalan")) {
            hier.allClasses().forEach(c -> {
                String n = c.getName();
                if (n.equals("javax.xml.parsers.FactoryFinder")
                        || n.equals("javax.xml.transform.FactoryFinder")) {
                    c.getDeclaredMethods().stream()
                            .filter(m -> m.getName().equals("newInstance"))
                            .forEach(roots::add);
                } else if (c.getSimpleName().equals("ObjectFactory")
                        && n.startsWith("org.apache.")) {
                    roots.addAll(c.getDeclaredMethods());
                }
            });
        } else if (slice.equals("pmd")) {
            JClass c = hier.getClass("net.sourceforge.pmd.RuleSetFactory");
            if (c != null) {
                roots.addAll(c.getDeclaredMethods());
            }
        } else {
            throw new IllegalArgumentException("unknown slice: " + slice);
        }
        return roots;
    }

    // ==================== the probe ====================

    @Test
    void measureConeClosureFeasibility() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("zeta0.probe"),
                "ζ0 probe disabled — run with ./gradlew zeta0");
        List<String> csv = new ArrayList<>();
        csv.add("slice,config,roots,closure_methods,open_calls,carved_calls,"
                + "median_cone,p90_cone,max_cone,median_resolved,p90_resolved,"
                + "max_resolved,refusal_pct,refusal_native,refusal_no_body,"
                + "refusal_exotic_indy,refusal_mh_vh_defineclass,top5_offenders");
        for (String slice : System.getProperty("zeta0.slices", "xalan,pmd").split(",")) {
            slice = slice.trim();
            long t0 = System.currentTimeMillis();
            Main.buildWorld("-java", "6",
                    "-acp", BENCH_DIR + slice + ".jar",
                    "-cp", BENCH_DIR + slice + "-deps.jar",
                    "-m", "Harness");
            ClassHierarchy hier = World.get().getClassHierarchy();
            System.out.printf("%n==== ζ0 slice %s: world %d classes (%d ms) ====%n",
                    slice, hier.allClasses().count(), System.currentTimeMillis() - t0);
            List<JMethod> roots = collectRoots(hier, slice);
            assertFalse(roots.isEmpty(), "no roots found for slice " + slice);
            System.out.printf("roots (%d) from classes: %s%n", roots.size(),
                    roots.stream().map(m -> m.getDeclaringClass().getName())
                            .distinct().sorted().collect(Collectors.joining(", ")));
            for (Mode mode : Mode.values()) {
                String config = switch (mode) {
                    case EXPAND_ALL -> "without-carveout";
                    case CARVE_OUT -> "with-carveout";
                    case NO_OPEN_EXPANSION -> "open-as-action(extra)";
                };
                long t1 = System.currentTimeMillis();
                ClosureResult r = computeClosure(hier, roots, mode);
                int[] cones = r.openCalls().stream()
                        .mapToInt(OpenCall::coneSize).sorted().toArray();
                int[] resolved = r.openCalls().stream()
                        .mapToInt(OpenCall::resolved).sorted().toArray();
                double refusalPct = r.closure().isEmpty() ? 0
                        : 100.0 * r.refusalN() / r.closure().size();
                String offenders = r.openCalls().stream()
                        .sorted(Comparator.comparingInt(OpenCall::coneSize).reversed())
                        .limit(5)
                        .map(o -> String.format("%s@%d %s.%s cone=%d resolved=%d",
                                o.container().getSignature(), o.stmtIndex(),
                                o.staticType(), callSimpleName(o.callee()),
                                o.coneSize(), o.resolved()))
                        .collect(Collectors.joining(" | "));
                csv.add(String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%s",
                        slice, config, roots.size(), r.closure().size(),
                        r.openCalls().size(), r.carvedCalls(),
                        percentile(cones, 0.5), percentile(cones, 0.9), max(cones),
                        percentile(resolved, 0.5), percentile(resolved, 0.9),
                        max(resolved), refusalPct, r.nativeN(), r.noBodyN(),
                        r.exoticIndyN(), r.mhVhDefineClassN(),
                        "\"" + offenders.replace("\"", "'") + "\""));
                long jdkN = r.closure().stream().filter(m -> {
                    String n = m.getDeclaringClass().getName();
                    return n.startsWith("java.") || n.startsWith("javax.")
                            || n.startsWith("sun.") || n.startsWith("com.sun.")
                            || n.startsWith("org.omg.") || n.startsWith("org.w3c.")
                            || n.startsWith("org.xml.");
                }).count();
                System.out.printf("[%s/%s] closure=%d (jdk=%d, %.1f%%) open=%d carved=%d "
                                + "cone(med/p90/max)=%d/%d/%d resolved(med/p90/max)=%d/%d/%d "
                                + "refusal=%.1f%% (native=%d noBody=%d indy=%d mhvh=%d) "
                                + "(%d ms)%n",
                        slice, config, r.closure().size(), jdkN,
                        r.closure().isEmpty() ? 0 : 100.0 * jdkN / r.closure().size(),
                        r.openCalls().size(),
                        r.carvedCalls(), percentile(cones, 0.5), percentile(cones, 0.9),
                        max(cones), percentile(resolved, 0.5), percentile(resolved, 0.9),
                        max(resolved), refusalPct, r.nativeN(), r.noBodyN(),
                        r.exoticIndyN(), r.mhVhDefineClassN(),
                        System.currentTimeMillis() - t1);
                System.out.println("  top offenders: " + offenders);
                if (r.closure().size() <= 1000 && r.refusalN() > 0) {
                    // small closure: name the refusal methods (β1 needs them)
                    r.closure().stream()
                            .filter(m -> m.isNative() || m.isAbstract()
                                    || m.getDeclaringClass().isPhantom())
                            .forEach(m -> System.out.println("  refusal: "
                                    + (m.isNative() ? "[native] " : "[no-body] ")
                                    + m.getSignature()));
                }
            }
        }
        Path out = Path.of(CSV_OUT);
        Files.createDirectories(out.getParent());
        Files.write(out, csv);
        System.out.println("\nCSV written: " + out.toAbsolutePath());
    }

    private static String callSimpleName(String callee) {
        int dot = callee.lastIndexOf('.', callee.lastIndexOf('.') - 1);
        return dot < 0 ? callee : callee.substring(dot + 1);
    }

    /** Nearest-rank percentile of a sorted array; 0 when empty. */
    private static int percentile(int[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static int max(int[] sorted) {
        return sorted.length == 0 ? 0 : sorted[sorted.length - 1];
    }
}
