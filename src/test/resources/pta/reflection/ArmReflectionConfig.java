// Scenario A fixture: the reflective class/method names are read from a config
// file (arm2cfg.properties) via Properties.getProperty(key). The name vars are
// config-driven (empty points-to). arm②'s ConfigResolver reads the .properties on
// the classpath for the extracted keys (plugin.class / plugin.method) and feeds
// the values as high-confidence candidates → PlugImpl.go becomes reachable.

import java.util.Properties;

public class ArmReflectionConfig {

    public static void main(String[] args) throws Exception {
        PlugImpl pi = new PlugImpl();
        Properties p = new Properties();
        p.load(ArmReflectionConfig.class.getResourceAsStream("arm2cfg.properties"));
        Class<?> c = Class.forName(p.getProperty("plugin.class"));
        java.lang.reflect.Method m = c.getMethod(p.getProperty("plugin.method"),
                java.lang.String.class);
        m.invoke(pi, "x");
    }
}

class PlugImpl {
    public String go(String s) {
        return mark(s);
    }

    static String mark(String s) {
        return s;
    }
}
