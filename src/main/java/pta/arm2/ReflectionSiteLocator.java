package pta.arm2;

import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Arm② §11 step 1 — self-flagging localization.
 *
 * <p>Given a (CI pre-)analysis result, enumerates reflective-action sites and
 * flags the <em>residual</em> ones the base analysis left unresolved (no callee).
 * These are exactly the sites where arm②'s LLM-as-annotator should be invoked;
 * sites the base already resolved (string constants, self-inferenceable) keep a
 * non-empty callee set and are NOT flagged, so the LLM is never wasted on them.
 */
public final class ReflectionSiteLocator {

    private ReflectionSiteLocator() {
    }

    /**
     * @param result a completed (CI pre-)analysis result
     * @return residual reflective-action sites (empty callee set), in IR order
     */
    public static List<FlaggedSite> locate(PointerAnalysisResult result) {
        CallGraph<Invoke, JMethod> cg = result.getCallGraph();
        List<FlaggedSite> flagged = new ArrayList<>();
        for (JMethod m : cg) {
            if (m.isAbstract() || m.getIR() == null) {
                continue;
            }
            for (Stmt s : m.getIR()) {
                if (s instanceof Invoke invoke) {
                    ReflectiveKind kind = kindOf(invoke.getMethodRef());
                    // Residual = a reflective-action site the base could not
                    // resolve to any target (no call edge added).
                    if (kind != null && cg.getCalleesOf(invoke).isEmpty()) {
                        flagged.add(new FlaggedSite(invoke, kind, m));
                    }
                }
            }
        }
        return flagged;
    }

    /** Classifies a call's method reference as a reflective action, or null. */
    private static ReflectiveKind kindOf(MethodRef ref) {
        String dc = ref.getDeclaringClass().getName();
        String name = ref.getName();
        return switch (dc) {
            case "java.lang.Class" ->
                    "newInstance".equals(name) ? ReflectiveKind.NEW_INSTANCE : null;
            case "java.lang.reflect.Constructor" ->
                    "newInstance".equals(name) ? ReflectiveKind.CONSTRUCTOR_NEW_INSTANCE : null;
            case "java.lang.reflect.Method" ->
                    "invoke".equals(name) ? ReflectiveKind.INVOKE : null;
            case "java.lang.reflect.Field" -> switch (name) {
                case "get" -> ReflectiveKind.FIELD_GET;
                case "set" -> ReflectiveKind.FIELD_SET;
                default -> null;
            };
            default -> null;
        };
    }
}
