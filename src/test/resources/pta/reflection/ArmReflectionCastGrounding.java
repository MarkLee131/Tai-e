// G2 use-site type grounding fixture (the gruntspud loadPlugins shape, in vitro):
// the reflectively loaded class is locally instantiated and downcast —
// (CastGroundPlugin) Class.forName(<unknown>).newInstance() — so the runtime class
// must be a concrete subtype of CastGroundPlugin. The prompt built for the site must
// carry that fact plus the sorted concrete-subtype enumeration, in BOTH pipelines
// (staged and legacy fire-once), with byte-identical prompt text.

public class ArmReflectionCastGrounding {

    public static void main(String[] args) throws Exception {
        CastGroundPlugin p = (CastGroundPlugin)
                Class.forName(new String(new char[]{'x'})).newInstance(); // Unknown → LLM
        p.start();
    }
}

interface CastGroundPlugin {
    void start();
}

class CastGroundImplA implements CastGroundPlugin {

    public CastGroundImplA() {
    }

    public void start() {
    }
}

class CastGroundImplB implements CastGroundPlugin {

    public CastGroundImplB() {
    }

    public void start() {
    }
}
