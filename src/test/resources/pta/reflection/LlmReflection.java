// Integration test program for Arm 2 (pta.arm2.LlmReflectionModel).
//
// The class name passed to Class.forName comes from a NON-constant string
// (built via StringBuilder), so Tai-e's reflection-inference:string-constant
// cannot resolve it. Consequently c.newInstance() resolves to no target and
// Service.<init> is NOT reachable under the baseline analysis.
//
// With the LLM arm enabled and an oracle that proposes "Service",
// TargetFilter admits Service.<init> and the model adds the missing call
// edge, making Service.<init> reachable (recall gain). With a garbage
// oracle, the filter drops the proposal and nothing changes (soundness).

public class LlmReflection {

    public static void main(String[] args) throws Exception {
        String name = obfuscate();
        Class<?> c = Class.forName(name);
        Object o = c.newInstance();
        sink(o);
    }

    // Returns "Service" but as a non-constant string so the string-constant
    // reflection inference cannot fold it.
    static String obfuscate() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ser");
        sb.append("vice");
        return sb.toString();
    }

    static void sink(Object o) {
    }
}

class Service {

    Service() {
    }

    void run() {
    }
}
