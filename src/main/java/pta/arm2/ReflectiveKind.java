package pta.arm2;

/**
 * The kind of reflective API site arm② can flag and resolve. Ordered roughly by
 * empirical frequency (TOSEM'19 Remark 6: newInstance + invoke ≈ 79% of
 * reflective-action sites).
 */
public enum ReflectiveKind {
    /** {@code Class.newInstance()} — no-arg reflective instantiation. */
    NEW_INSTANCE,
    /** {@code java.lang.reflect.Method.invoke(recv, args)}. */
    INVOKE,
    /** {@code java.lang.reflect.Constructor.newInstance(args)}. */
    CONSTRUCTOR_NEW_INSTANCE,
    /** {@code java.lang.reflect.Field.get(obj)}. */
    FIELD_GET,
    /** {@code java.lang.reflect.Field.set(obj, val)}. */
    FIELD_SET,
}
