// S5: getProperty(key, default)'s ⊤ must be emitted at the SITE, not gated on the default
// arg's points-to. Here the default is FULLY opaque (StringBuilder → empty pts), so the
// arg-gated handler never fires and today contributes NOTHING — while a constant reaches
// the forName name var from the other branch, so the name var is non-empty (consumer-side
// empty-pts seeding never fires) and carries no Unknown obj (LLM suppressed). Without
// site-level ⊤, a runtime-set property value is silently missed.

public class ArmReflectionEnvOpaque {

    public static void main(String[] args) throws Exception {
        String name;
        if (args.length > 0) {
            name = "Decoy2"; // distinguished constant reaches the name var directly
        } else {
            name = System.getProperty("impl.key",
                    new StringBuilder().append("X").toString()); // fully opaque default
        }
        Class<?> c = Class.forName(name);
        c.newInstance();
    }
}

class Decoy2 {

    public Decoy2() {
    }
}

class EnvTarget2 {

    public EnvTarget2() {
        hit();
    }

    static void hit() {
    }
}
