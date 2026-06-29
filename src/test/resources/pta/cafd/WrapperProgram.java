/**
 * Test program for the CAFD allocator-wrapper baseline (B3).
 *
 * make()   — pure allocator wrapper: allocates and returns a fresh Data,
 *             no non-local side effects.
 * mutate() — NOT a wrapper: writes to the static field {@code leaked}
 *             (escaping side effect), so WrapperDetector must reject it.
 *
 * With cs:ci, both {@code a} and {@code b} point to the same abstract
 * object (the one allocation site inside make()), so they alias.
 * With advanced:cafd, each call site of make() gets its own per-callsite
 * MockObj, so {@code a} and {@code b} point to distinct objects.
 */
public class WrapperProgram {

    static class Data {}

    static Object leaked;

    /** Allocator wrapper: allocates and returns a fresh Data. */
    static Data make() {
        return new Data();
    }

    /** Non-wrapper: has a static-field write (escaping side effect). */
    static Data mutate() {
        Data d = new Data();
        leaked = d;
        return d;
    }

    public static void main(String[] args) {
        Data a = make();   // call site 1
        Data b = make();   // call site 2
        Data c = mutate(); // not a wrapper call
        use(a);
        use(b);
        use(c);
    }

    static void use(Object o) {}
}
