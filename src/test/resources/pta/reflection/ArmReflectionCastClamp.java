// P1 (formal §6 Clamp): when forName(...).newInstance() is post-dominated by a downcast,
// the cast type is a SOUND bound on the instance (A3) — subtype expansion must inject only
// instantiable subtypes ≼ the bound. ClampBase has two impls; only ClampA implements the
// cast interface, so ClampB must NOT be injected (precision), while ClampA must (recall).

public class ArmReflectionCastClamp {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName(new String(new char[]{'x'})); // Unknown → LLM → ClampBase
        INarrow s = (INarrow) c.newInstance(); // straight-line post-dominating downcast
        s.go();
    }
}

interface INarrow {
    void go();
}

abstract class ClampBase {
}

class ClampA extends ClampBase implements INarrow {

    public ClampA() {
    }

    public void go() {
        aHit();
    }

    static void aHit() {
    }
}

class ClampB extends ClampBase {

    public ClampB() {
        bHit();
    }

    static void bHit() {
    }
}
