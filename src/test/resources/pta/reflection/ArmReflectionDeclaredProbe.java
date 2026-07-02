// S6: at a getDeclaredMethod site, the "did the proposal resolve?" probe must use the
// SAME declared-variant lookup as the injection. "inherited" is public on the SUPERCLASS:
// getMethods(Sub) finds it (probe says resolved) but getDeclaredMethods(Sub) injects
// nothing — so the site resolves zero targets yet would not be flagged. It must be flagged.

public class ArmReflectionDeclaredProbe {

    public static void main(String[] args) throws Exception {
        String name = new String(new char[]{'z'}); // Unknown
        java.lang.reflect.Method m = Sub.class.getDeclaredMethod(name);
        m.invoke(new Sub());
    }
}

class Base {

    public void inherited() {
    }
}

class Sub extends Base {
}
