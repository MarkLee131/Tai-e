// The residual antlr case: Class.forName(name).newInstance() where the resolved name is
// an ABSTRACT class (antlr.Tool.doEverything → antlr.CodeGenerator, whose real runtime
// objects are the concrete Cpp/CSharp/JavaCodeGenerator). forName+newInstance cannot
// instantiate an abstract class, so arm② must expand an abstract/interface target to its
// concrete subclasses (via the class hierarchy).

public class ArmReflectionAbstract {

    // Reference the subclasses so they are loaded into the hierarchy (but do NOT call
    // their methods — those become reachable only if the expansion instantiates them).
    static final Class<?>[] KEEP = {AbsImplA.class, AbsImplB.class};

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName(new String(new char[]{'x'})); // Unknown → LLM → AbsBase
        c.newInstance();
    }
}

abstract class AbsBase {

    public AbsBase() {
        init();
    }

    abstract void init();
}

class AbsImplA extends AbsBase {
    void init() {
        pingA();
    }

    static void pingA() {
    }
}

class AbsImplB extends AbsBase {
    void init() {
        pingB();
    }

    static void pingB() {
    }
}
