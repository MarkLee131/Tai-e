// D3 fixture: app-level ServiceLoader use. META-INF/services/Plugin lists PluginImpl.
// ServiceLoaderModel must read it, instantiate the provider, and wire load→iterator→
// next so p.run() dispatches to PluginImpl.run (→ done() reachable).

import java.util.ServiceLoader;

public class ArmReflectionService {

    public static void main(String[] args) {
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            p.run();
        }
    }
}

interface Plugin {
    void run();
}

class PluginImpl implements Plugin {
    public void run() {
        done();
    }

    static void done() {
    }
}
