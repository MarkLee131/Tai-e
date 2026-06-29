// Benchmark for the Arm 3 (neuro-symbolic) robustness sweep.
//
// A STATIC identity wrapper id() with SINGLE-GROUP arguments and no other
// conflation path. Under context-INSENSITIVE analysis id() is analysed once and
// its parameter conflates {P, Q} across the two call sites, so:
//   pts(xp) = pts(xq) = {P, Q}
//   xp.m() dispatches to P.m() AND Q.m()   (Q.m edge is spurious)
//   xq.m() dispatches to P.m() AND Q.m()   (P.m edge is spurious)
//
// A CORRECT LLM fact ("wrapper id" + "never-alias P Q") lets the sound arm 3
// disposer de-conflate: arg p is provably single-group P per the CI pre-analysis
// (pts(p) = {P}), so banning Q from xp removes only the spurious Q.m edge —
// SOUND. p=0 ground truth therefore drops the two spurious edges.
//
// A WRONG fact (any error rate > 0) is rejected/withheld by the disposer, so the
// analysis falls back to the (sound) CI super-set. Recall vs the p=0 ground
// truth stays 1.0 at every error rate: arm 3 is sound by construction.

interface T {
    void m();
}

class P implements T {
    public void m() {
    }
}

class Q implements T {
    public void m() {
    }
}

public class Arm3Sweep {

    /** Static identity wrapper — context-conflated under CI. */
    static T id(T t) {
        return t;
    }

    public static void main(String[] args) {
        T p = new P();
        T q = new Q();
        T xp = id(p);     // CI: xp -> {P, Q}; correct fact refines to {P}
        T xq = id(q);     // CI: xq -> {P, Q}; correct fact refines to {Q}
        xp.m();           // CI: P.m, Q.m  (Q.m spurious)
        xq.m();           // CI: P.m, Q.m  (P.m spurious)
    }
}
