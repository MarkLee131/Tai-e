// G7 soundness: Class.forName(System.getProperty(key, Default.class.getName())).
// getProperty(key, default) returns the DEFAULT only if the property is unset; if it is
// set at runtime (env / -Dkey=... / config) it returns THAT value — a different class.
// Flowing only the default is UNDER-approximate (unsound): it misses the env-set class.
// The sound model emits default ⊔ ⊤, where ⊤ (an Unknown string) routes the forName to
// the residual/oracle, so the env-set target can still be recovered.

public class ArmReflectionEnvProperty {

    public static void main(String[] args) throws Exception {
        String name = System.getProperty("my.impl.class", DefaultImpl.class.getName());
        Class<?> c = Class.forName(name);
        c.newInstance();
    }
}

class DefaultImpl {
    public DefaultImpl() {
        base();
    }

    static void base() {
    }
}

class EnvTarget {
    public EnvTarget() {
        hit();
    }

    static void hit() {
    }
}
