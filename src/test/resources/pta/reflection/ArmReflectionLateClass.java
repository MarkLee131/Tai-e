// S4: classes can reach a getMethod base var in DIFFERENT solver iterations. Early.class
// is a constant (arrives first); Late arrives only after the forName chain is resolved by
// the oracle and propagated through extra hops. The LLM's method proposal, obtained at the
// first (Early-only) fire, must ALSO be injected for the late-arriving Late class — the
// fire-once query latch must not skip injection for new classes.

public class ArmReflectionLateClass {

    public static void main(String[] args) throws Exception {
        Class<?> c;
        if (args.length > 0) {
            c = Early.class; // constant: present from the first rounds
        } else {
            c = hop3(); // Late: resolved via oracle + 3 hops → later iteration
        }
        String name = new String(new char[]{'y'}); // Unknown method name
        java.lang.reflect.Method m = c.getMethod(name);
        m.invoke(c.newInstance());
    }

    static Class<?> hop3() throws Exception {
        return hop2();
    }

    static Class<?> hop2() throws Exception {
        return hop1();
    }

    static Class<?> hop1() throws Exception {
        return Class.forName(new String(new char[]{'x'})); // oracle → Late
    }
}

class Early {

    public Early() {
    }

    public void go() {
        eHit();
    }

    static void eHit() {
    }
}

class Late {

    public Late() {
    }

    public void go() {
        lHit();
    }

    static void lHit() {
    }
}
