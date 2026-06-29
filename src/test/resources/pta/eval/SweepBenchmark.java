// Benchmark for RobustnessSweep tests.
//
// Two runner classes ARunner and BRunner both implement the Runner interface.
// The static identity wrapper id(Runner r) returns its argument unchanged.
//
// Under context-INSENSITIVE analysis, id() is analysed once: its parameter
// merges both ARunner and BRunner objects, so pts(wa) = pts(wb) = {ARunner, BRunner}.
// Both wa.run() and wb.run() dispatch to BOTH ARunner.run() and BRunner.run()
// — four virtual-dispatch call edges in total.
//
// Under 2-object-sensitive analysis (arm① with all-YES oracle), the two
// allocation sites of 'a' and 'b' separate the contexts of id(). In the
// ARunner context pts(r) = {ARunner}, so pts(wa) = {ARunner}; in the BRunner
// context pts(wb) = {BRunner}. This gives only TWO virtual-dispatch edges:
//   wa.run() → ARunner.run()
//   wb.run() → BRunner.run()
//
// Arm① soundness: every 2-obj edge also exists in CI (CI ⊇ 2-obj), so recall
// vs the p=0 (2-obj) ground truth stays 1.0 for any corrupt CS-selection oracle.
//
// UnsoundCafdStylePlugin contrast: at p=1 the corrupt oracle returns
// "wrapper id / never-alias ARunner ARunner". The plugin applies this without
// the ConsistencyEngine check that arm③ would perform. The self-referential
// never-alias fact causes ARunner objects to be filtered from pts(wa), so
// wa.run() can no longer dispatch to ARunner.run() — a real call edge is lost
// and recall drops below 1.0.

interface Runner {
    void run();
}

class ARunner implements Runner {
    public void run() {}
}

class BRunner implements Runner {
    public void run() {}
}

public class SweepBenchmark {

    /** Structural identity wrapper: returns its argument unchanged. */
    static Runner id(Runner r) {
        return r;
    }

    public static void main(String[] args) {
        ARunner a = new ARunner();
        BRunner b = new BRunner();
        Runner wa = id(a); // under CI: wa pts = {ARunner, BRunner}
        Runner wb = id(b); // under CI: wb pts = {ARunner, BRunner}
        wa.run();          // dispatch site 1 — real targets: ARunner.run (+ BRunner.run under CI)
        wb.run();          // dispatch site 2 — real targets: BRunner.run (+ ARunner.run under CI)
    }
}
