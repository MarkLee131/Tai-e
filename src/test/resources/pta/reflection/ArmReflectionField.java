// Functional fixture for arm②'s sound reflective field-get chain.
//
// Class name and field name are Unknown (new String). With reflection-inference:llm
// and an oracle supplying "Holder" / "payload", arm② resolves them, injects the
// Class/Field metaobjects, and Tai-e's ReflectiveActionModel models f.get(h) as
// the field read h.payload — so v points to the Payload and ((Payload)v).run()
// dispatches to Payload.run() (reachable only if the field value flowed through).

public class ArmReflectionField {

    public static void main(String[] args) throws Exception {
        Holder h = new Holder();
        h.payload = new Payload();
        Class<?> c = Class.forName(unknown("Holder"));
        java.lang.reflect.Field f = c.getField(unknown("payload"));
        Object v = f.get(h);
        ((Payload) v).run();   // Payload.run reachable iff the field value flowed to v
    }

    static String unknown(String s) {
        return new String(s);
    }
}

class Holder {
    public Payload payload;
}

class Payload {
    public void run() {
        sink();
    }

    static void sink() {
    }
}
