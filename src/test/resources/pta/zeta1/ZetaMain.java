/**
 * The zeta-1 fixture app — the client that CONSUMES the library boundary.
 * Without admitted summaries the points-to sets of r1/b1/b2 are empty
 * (ZLib is opaque); with admitted summaries they are populated by
 * materialization (spec Definition II.25).
 */
public class ZetaMain {

    public static void main(String[] args) {
        Object o = new Object();      // app allocation
        Object r1 = ZLib.id(o);       // ret-alias: pts(r1) = pts(o)
        ZBox b1 = ZLib.make();        // ret-fresh: pts(b1) = { o_a }
        ZBox b2 = ZLib.wrap();        // same SITE-INDEXED o_a as b1
        ZLib.each(new ZTask());       // callback: ZTask.run becomes reachable
        use(o, r1, b1, b2);
    }

    static void use(Object a, Object b, Object c, Object d) {
    }
}
