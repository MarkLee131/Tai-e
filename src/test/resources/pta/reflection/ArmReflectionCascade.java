// B-wave two-stage cascade (the antlr shape, in vitro): site B lives in Stage1.go(),
// which becomes reachable ONLY via site A's injection — so resolving both requires
// >= 2 staged phases: phase 1 latches site A; phase-finish 1 injects Stage1;
// phase 2 reaches Stage1.go and latches site B; phase-finish 2 injects Stage2;
// phase 3 propagates Stage2.leaf.

public class ArmReflectionCascade {

    public static void main(String[] args) throws Exception {
        Stage1 s = (Stage1) Class.forName(new String(new char[]{'x'})).newInstance(); // Unknown → LLM → Stage1
        s.go();
    }
}

class Stage1 {

    public Stage1() {
    }

    public void go() throws Exception {
        Stage2 t = (Stage2) Class.forName(new String(new char[]{'y'})).newInstance(); // Unknown → LLM → Stage2
        t.leaf();
    }
}

class Stage2 {

    public Stage2() {
    }

    public void leaf() {
    }
}
