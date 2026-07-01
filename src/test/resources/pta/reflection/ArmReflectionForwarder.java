// Reproduces the lucene SegmentReader/FSDirectory idiom that dominates the DaCapo
// reflection-recall gap:
//     IMPL = Class.forName(System.getProperty(key, DefaultClass.class.getName()))
// Resolving it needs the self-inferencing chain:
//     FwdTarget.class -> Class -> getName() -> "FwdTarget"  (string constant)
//     System.getProperty(key, "FwdTarget") -> "FwdTarget"   (default flows through)
//     Class.forName("FwdTarget") -> FwdTarget -> IMPL.newInstance() -> <init> -> init()
// Without modeling Class.getName() and System.getProperty(k,default), the chain breaks
// and FwdTarget.init is unreachable.

public class ArmReflectionForwarder {

    static Class<?> IMPL;

    static {
        Class<?> c;
        try {
            c = Class.forName(System.getProperty("arm2.fwd.impl",
                    FwdTarget.class.getName()));
        } catch (ClassNotFoundException e) {
            c = null;
        }
        IMPL = c;
    }

    public static void main(String[] args) throws Exception {
        Object o = IMPL.newInstance();
        System.out.println(o);
    }
}

class FwdTarget {

    public FwdTarget() {
        init();
    }

    void init() {
    }

    public String ping() {
        return "p";
    }
}
