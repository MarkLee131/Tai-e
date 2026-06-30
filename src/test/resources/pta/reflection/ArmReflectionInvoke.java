// Functional fixture for arm②'s sound invoke chain via metaobject injection.
//
// Both the class name (forName) and the method name (getMethod) are Unknown:
// `new String(const)` yields a tracked-but-non-constant String object, so
// string-constant inference cannot resolve them (CSObjs.toString == null) and
// GreeterImpl.greet is NOT reachable at baseline. With reflection-inference:llm
// and an oracle supplying "GreeterImpl" / "greet", arm② injects the Class/Method
// metaobjects and Tai-e's ReflectiveActionModel builds the invoke edge (with arg
// flow): GreeterImpl.greet becomes reachable, and so does sink() in its body.

public class ArmReflectionInvoke {

    public static void main(String[] args) throws Exception {
        GreeterImpl g = new GreeterImpl();
        Class<?> c = Class.forName(unknown("GreeterImpl"));
        java.lang.reflect.Method m = c.getMethod(unknown("greet"), java.lang.String.class);
        m.invoke(g, "hi");
    }

    // new String(const) → a tracked String object that is NOT a string constant.
    static String unknown(String s) {
        return new String(s);
    }
}

class GreeterImpl {
    // public: Class.getMethod (and Tai-e's Reflections.getMethods) only return
    // public methods.
    public String greet(String s) {
        return sink(s);   // reachable only if the invoke edge to greet() fires
    }

    static String sink(String s) {
        return s;
    }
}
