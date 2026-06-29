package pta.arm2;

import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The soundness gate for Arm 2 (LLM reflection / indirect-call resolution).
 * <p>
 * Tai-e has no API to remove a call edge, so Arm 2 may only <em>add</em>
 * targets. This filter is what makes those additions safe: an LLM-proposed
 * class name is admitted only if it is
 * <ol>
 *     <li><b>loaded</b> in the class hierarchy (otherwise dropped), and</li>
 *     <li><b>type-compatible</b> with the call site, defining (or inheriting)
 *         a method matching the site's expected signature.</li>
 * </ol>
 * A wrong LLM answer can therefore at most add a type-valid-but-irrelevant
 * edge (a precision cost); it can never drop a real edge and never inject a
 * type-incompatible target. The LLM output never bypasses this gate.
 */
public class TargetFilter {

    /**
     * @param site        the (reflective or indirect) call site being resolved
     * @param llmProposed candidate fully-qualified class names proposed by the LLM
     * @param h           the class hierarchy
     * @param ts          the type system
     * @return the subset of concrete methods that are loaded and
     * signature/type-compatible with {@code site}. Everything else is dropped.
     */
    public Set<JMethod> admit(Invoke site, List<String> llmProposed,
                              ClassHierarchy h, TypeSystem ts) {
        Set<JMethod> admitted = new LinkedHashSet<>();
        if (site == null || llmProposed == null) {
            return admitted;
        }
        MethodRef ref = site.getMethodRef();
        boolean reflectiveNew = isReflectiveNewInstance(ref);
        Type recvType = ref.getDeclaringClass().getType();
        Subsignature subsig = ref.getSubsignature();
        for (String raw : llmProposed) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            JClass clazz = h.getClass(name);
            if (clazz == null) {
                // not loaded -> drop (soundness: never fabricate an edge to an
                // unknown class)
                continue;
            }
            JMethod target;
            if (reflectiveNew) {
                // Class.newInstance()/Constructor.newInstance(): expected target
                // is the no-argument constructor of the proposed class.
                target = clazz.getDeclaredMethod(Subsignature.getNoArgInit());
            } else {
                // Ordinary indirect call (e.g. Method.invoke or a virtual call
                // whose receiver type is over-approximated): require the proposed
                // class to be assignable to the site's declared receiver type and
                // to dispatch to a concrete method with the expected subsignature.
                if (!ts.isSubtype(recvType, clazz.getType())) {
                    continue;
                }
                target = h.dispatch(clazz.getType(), ref);
                if (target == null) {
                    target = clazz.getDeclaredMethod(subsig);
                }
            }
            if (target == null || target.isAbstract()) {
                continue;
            }
            admitted.add(target);
        }
        return admitted;
    }

    private static boolean isReflectiveNewInstance(MethodRef ref) {
        String dc = ref.getDeclaringClass().getName();
        String name = ref.getName();
        return "newInstance".equals(name)
                && ("java.lang.Class".equals(dc)
                || "java.lang.reflect.Constructor".equals(dc));
    }
}
