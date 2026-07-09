package pta.baseline.cafd;

import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.StaticFieldAccess;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ReferenceType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects <em>allocator-wrapper</em> methods from a CI pre-analysis result
 * (B3 baseline — deterministic, no LLM).
 *
 * <h2>Detection criterion</h2>
 * A method M is classified as an allocator wrapper if <em>all</em> of the
 * following hold:
 * <ol>
 *   <li>M has a reference return type.</li>
 *   <li>M contains at least one {@code new} statement.</li>
 *   <li>Every object in the points-to set of every return variable of M was
 *       allocated inside M itself (i.e., its container method is M). If any
 *       return variable has an empty points-to set the method is not a
 *       wrapper (conservative).</li>
 *   <li>M contains no {@code StoreField} to a static field (no static
 *       side effects).</li>
 *   <li>M contains no {@code StoreField} where the base's points-to set
 *       includes an object not allocated in M (no writes to fields of
 *       escaped objects).</li>
 * </ol>
 *
 * <p><b>Soundness note:</b> B3 is a <em>baseline to beat</em>, not a
 * formally sound approximation. Criteria 3 and 5 are computed from the CI
 * points-to sets, which are themselves over-approximate; false positives
 * (methods wrongly classified as wrappers) could in principle make the CAFD
 * result unsound. The LLM-based arms (①②) are the contributions; B3 exists
 * solely as a deterministic comparison point.
 */
public class WrapperDetector {

    private WrapperDetector() {
    }

    /**
     * Detects allocator-wrapper methods from a CI pre-analysis result.
     *
     * @param preAnalysis CI pre-analysis result (provides points-to information)
     * @param h           class hierarchy — reserved for future use (e.g., checking
     *                    whether a method has overrides that could escape); not
     *                    consulted by the current implementation
     * @return deterministic, insertion-ordered set of detected wrapper methods
     */
    public static Set<JMethod> detect(PointerAnalysisResult preAnalysis,
                                       @SuppressWarnings("unused") ClassHierarchy h) {
        Set<JMethod> wrappers = new LinkedHashSet<>();
        List<JMethod> candidates = preAnalysis.getCallGraph()
                .reachableMethods()
                .filter(m -> !m.isAbstract() && m.getIR() != null)
                .collect(Collectors.toList());
        for (JMethod m : candidates) {
            if (isWrapper(m, preAnalysis)) {
                wrappers.add(m);
            }
        }
        return wrappers;
    }

    // ------------------------------------------------------------------ //
    //  Private helpers
    // ------------------------------------------------------------------ //

    private static boolean isWrapper(JMethod m, PointerAnalysisResult pta) {
        // (1) Reference return type required
        if (!(m.getReturnType() instanceof ReferenceType)) {
            return false;
        }

        IR ir = m.getIR();

        // (2) Must contain at least one New statement
        boolean hasNew = ir.getStmts().stream().anyMatch(s -> s instanceof New);
        if (!hasNew) {
            return false;
        }

        // (3) All return variables point only to objects allocated inside m
        List<Var> retVars = ir.getReturnVars();
        if (retVars.isEmpty()) {
            return false;
        }
        for (Var retVar : retVars) {
            Set<Obj> pts = pta.getPointsToSet(retVar);
            if (pts.isEmpty()) {
                // Conservative: if PTA has no information, reject
                return false;
            }
            for (Obj obj : pts) {
                boolean allocatedInM = obj.getContainerMethod()
                        .map(cm -> cm.equals(m))
                        .orElse(false);
                if (!allocatedInM) {
                    return false;
                }
            }
        }

        // (4) No StoreField to a static field
        for (var stmt : ir.getStmts()) {
            if (stmt instanceof StoreField sf
                    && sf.getFieldAccess() instanceof StaticFieldAccess) {
                return false;
            }
        }

        // (5) No StoreField to a field of a non-locally-allocated object
        for (var stmt : ir.getStmts()) {
            if (stmt instanceof StoreField sf
                    && sf.getFieldAccess() instanceof InstanceFieldAccess ifa) {
                Var base = ifa.getBase();
                for (Obj obj : pta.getPointsToSet(base)) {
                    boolean localObj = obj.getContainerMethod()
                            .map(cm -> cm.equals(m))
                            .orElse(false);
                    if (!localObj) {
                        return false;
                    }
                }
            }
        }

        return true;
    }
}
