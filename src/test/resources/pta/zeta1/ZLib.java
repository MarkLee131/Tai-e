/**
 * The zeta-1 fixture "library" — the S slice of the M1 formal core
 * (spec Part II, section II.1.1) at fixture scale. Its methods exercise
 * exactly the fixture summary language:
 * <ul>
 *   <li>{@link #id}   — ret-alias(p0): delegation of a parameter;</li>
 *   <li>{@link #make} — ret-fresh(a, ZBox): a slice allocation escaping
 *       through the return (site a is make's `new ZBox`);</li>
 *   <li>{@link #wrap} — two-method chain: returns make()'s fresh object,
 *       so its summary names a site NOT in its own body (the B1
 *       cross-method site-indexed marker, spec Definition II.12/II.14);</li>
 *   <li>{@link #each} — one callback: invokes the Runnable parameter
 *       (callback atom, spec Definition II.9(d) / II.23 app part).</li>
 * </ul>
 * The client analysis treats this class as opaque (bodies ignored);
 * only the checker reads the bodies.
 */
public class ZLib {

    /** ret-alias(0) */
    public static Object id(Object p) {
        return p;
    }

    /** ret-fresh(site "make/new ZBox/0", ZBox) */
    public static ZBox make() {
        return new ZBox();
    }

    /** two-method chain: ret-fresh of make()'s site (cross-method, B1) */
    public static ZBox wrap() {
        return make();
    }

    /** callback(0, "void run()") */
    public static void each(Runnable r) {
        r.run();
    }
}
