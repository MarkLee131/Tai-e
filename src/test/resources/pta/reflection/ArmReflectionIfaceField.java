// S1: Class.getField's resolution order is: this class → superinterfaces (recursively)
// → superclass. Constants declared in implemented interfaces (the most common getField
// target) must be found. HANDLER is declared on the interface Cfg; Impl implements Cfg.

public class ArmReflectionIfaceField {

    public static void main(String[] args) throws Exception {
        String name = new String(new char[]{'f'}); // Unknown → LLM → "HANDLER"
        java.lang.reflect.Field f = Impl.class.getField(name);
        System.out.println(f);
    }
}

interface Cfg {
    String HANDLER = "h";
}

class Impl implements Cfg {
}
