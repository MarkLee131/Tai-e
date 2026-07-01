// G1 soundness: when a forName target is abstract, injectClassAndSubtypes expands to its
// concrete subclasses, but caps at MAX_SUBTYPES and SILENTLY breaks — dropping the rest,
// which can drop a TRUE runtime target (under-approximate / unsound). The sound treatment
// per the formal model is to WIDEN (over-approximate) on overflow, never truncate.
// OverBase has 3 concrete subtypes with distinct methods; with the cap lowered to 2, all
// three must remain reachable (none silently dropped).

public class ArmReflectionSubtypeOverflow {

    // reference the subtypes so they load into the hierarchy
    static final Class<?>[] KEEP = {OverA.class, OverB.class, OverC.class};

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName(new String(new char[]{'x'})); // Unknown → LLM → OverBase
        c.newInstance();
    }
}

abstract class OverBase {
    OverBase() {
        init();
    }

    abstract void init();
}

class OverA extends OverBase {
    void init() {
        aM();
    }

    static void aM() {
    }
}

class OverB extends OverBase {
    void init() {
        bM();
    }

    static void bM() {
    }
}

class OverC extends OverBase {
    void init() {
        cM();
    }

    static void cM() {
    }
}
