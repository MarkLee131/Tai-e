// arm② app-context fixture: the reflective class name is Unknown (read from an
// unmodeled source → empty points-to). Only an LLM that is told the *application
// identity* can propose the right class (a convention-driven name). This mirrors
// DaCapo's dacapo.TestHarness.findClass → dacapo.<id>.<Id>Harness bootstrap, whose
// name derives from the benchmark identity (cmdline/config), not from the method body.

public class ArmReflectionAppContext {

    public static void main(String[] args) throws Exception {
        String name = pick();
        Class<?> c = Class.forName(name);
        c.newInstance();
    }

    static String pick() {
        return System.getenv("GADGET_CLASS"); // Unknown value, empty points-to
    }
}

class Gadget {

    public Gadget() {
        boot();
    }

    static void boot() {
    }
}
