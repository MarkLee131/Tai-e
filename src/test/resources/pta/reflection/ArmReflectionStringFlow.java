// Scenario B fixture: the reflective class/method names are built via StringBuilder,
// so the name vars have an EMPTY points-to set (string ops not modeled) and the
// pts-driven @InvokeHandler never fires. arm②'s empty-pts path must backward-trace
// the constant fragments ("Widget"+"Impl", "ren"+"der"), feed them to the LLM, and
// resolve — making WidgetImpl.render (and tag() in its body) reachable.

public class ArmReflectionStringFlow {

    public static void main(String[] args) throws Exception {
        WidgetImpl w = new WidgetImpl();
        Class<?> c = Class.forName(new StringBuilder().append("Widget").append("Impl").toString());
        java.lang.reflect.Method m =
                c.getMethod(new StringBuilder().append("ren").append("der").toString(),
                        java.lang.String.class);
        m.invoke(w, "x");
    }
}

class WidgetImpl {
    public String render(String s) {
        return tag(s);
    }

    static String tag(String s) {
        return s;
    }
}
