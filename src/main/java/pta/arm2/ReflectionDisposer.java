package pta.arm2;

import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Arm② §11 step 3 — the two-layer sound disposer: composes the base
 * {@link TargetFilter} (loaded + type-compatible) with a use-site type-bound
 * clamp (Elf's "self-inferencing" constraint), admitting only LLM-proposed
 * targets whose declaring class is consistent with the post-dominating downcast
 * (newInstance) or the receiver type (invoke/get/set).
 *
 * <h2>Soundness</h2>
 * <p>The clamp only ever <em>narrows</em> what arm② <em>adds</em>; arm② never
 * removes a base-resolved edge, so the clamp cannot reduce base recall. Under
 * the standard correct-casts assumption (Elf/SOLAR A3 — a downcast on a
 * reflective result does not throw), a real target's object is assignable to the
 * downcast type, so clamping to subtypes of the bound does not drop any real
 * target either; it only filters precision-irrelevant LLM proposals.
 */
public final class ReflectionDisposer {

    private static final TargetFilter FILTER = new TargetFilter();

    private ReflectionDisposer() {
    }

    public static Set<JMethod> admit(FlaggedSite site, List<String> llmProposed,
                                     ReflectionEvidence evidence,
                                     ClassHierarchy h, TypeSystem ts) {
        Set<JMethod> base = FILTER.admit(site.site(), llmProposed, h, ts);
        Type bound = boundType(site, evidence, h);
        if (bound == null) {
            // No use-site bound -> base type-validity only (sound, less precise).
            return base;
        }
        Set<JMethod> clamped = new LinkedHashSet<>();
        for (JMethod m : base) {
            Type declType = m.getDeclaringClass().getType();
            if (ts.isSubtype(bound, declType)) { // declType <: bound
                clamped.add(m);
            }
        }
        return clamped;
    }

    /**
     * The use-site type bound for a site: the downcast type for instantiation,
     * the receiver type for member access. Returns null when absent or when the
     * bound name does not resolve to a loaded class (then no clamp is applied).
     */
    private static Type boundType(FlaggedSite site, ReflectionEvidence ev, ClassHierarchy h) {
        String name = switch (site.kind()) {
            case NEW_INSTANCE, CONSTRUCTOR_NEW_INSTANCE -> ev.downcastType();
            case INVOKE, FIELD_GET, FIELD_SET -> ev.receiverType();
        };
        if (name == null) {
            return null;
        }
        JClass clazz = h.getClass(name);
        return clazz == null ? null : clazz.getType();
    }
}
