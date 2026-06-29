// Benchmark for RobustnessSweep tests.
//
// Two runner classes ARunner and BRunner both implement the Runner interface.
//
// INSTANCE WRAPPER (RunnerBox) — for precision-gap detection in the sound arm1 test:
// ─────────────────────────────────────────────────────────────────────────────────
// RunnerBox.get() is an INSTANCE method whose receiver (box1 vs box2) determines
// the 2-object-sensitive heap context, just like BoxAlias.Box.get().
//
// Under context-INSENSITIVE analysis, get() is analysed once: its field
// 'stored' merges ARunner and BRunner objects, so
//   pts(wa) = pts(wb) = {ARunner, BRunner}  (each size 2).
//
// Under 2-object-sensitive analysis (arm① with all-YES oracle), box1 and box2
// are different allocation sites → different heap contexts for get():
//   box1-ctx: stored → {ARunner-alloc} → wa → {ARunner}  (size 1)
//   box2-ctx: stored → {BRunner-alloc} → wb → {BRunner}  (size 1)
//
// This precision gap (wa/wb size-2 under CI vs size-1 under 2-obj) drives the
// avgPtsSize inequality that proves the sound arm1 sweep test is non-vacuous:
//   sweep[p=1].m().avgPtsSize()  >  sweep[p=0].m().avgPtsSize()
//
// Arm① soundness: CI ⊇ 2-obj — every 2-obj edge also exists under CI, so
// recall vs the p=0 ground truth stays 1.0 at every error rate.
//
// STATIC WRAPPER (id) — for UnsoundCafdStylePlugin contrast test:
// ────────────────────────────────────────────────────────────────
// The static identity method id() is kept so that the corrupt oracle
// "wrapper id\nnever-alias ARunner ARunner" can identify it as a wrapper and
// apply a self-contradictory never-alias fact without a consistency check.
// Because id() is static, 2-obj does NOT separate its contexts — both id(a)
// and id(b) calls execute in the same static context, and the result variables
// xa and xb point to {ARunner, BRunner} regardless of the CS level.
//
// UnsoundCafdStylePlugin contrast:
// At p≥threshold (seed=42), the corrupt oracle fires; the plugin applies
// "never-alias ARunner ARunner" without a ConsistencyEngine check. This bans
// ARunner objects from xa (the result of id(a) where arg-group=ARunner),
// causing xa.run() to miss ARunner.run → recall drops below 1.0.
// Arm③ with the same corrupt oracle would reject the self-contradictory fact
// and keep recall = 1.0.
//
// Ground-truth (p=0, no oracle for CAFD test):
//   xa.run() → ARunner.run           (1 edge)
//   xb.run() → BRunner.run           (1 edge)
//   wa.run() → ARunner.run, BRunner.run (2 edges, CI)
//   wb.run() → ARunner.run, BRunner.run (2 edges, CI)
//   Total: 6 virtual-dispatch edges
//
// After corrupt oracle "never-alias ARunner ARunner" on id():
//   xa → {} (ARunner filtered), xa.run() → 0 edges  (1 edge dropped)
//   recall = 5/6 ≈ 0.83 < 1.0.

interface Runner {
    void run();
}

class ARunner implements Runner {
    public void run() {}
}

class BRunner implements Runner {
    public void run() {}
}

/** Instance wrapper — enables 2-obj context separation (reuses BoxAlias idiom). */
class RunnerBox {
    private Runner stored;

    void put(Runner r) {
        this.stored = r;
    }

    Runner get() {
        return this.stored;
    }
}

public class SweepBenchmark {

    /**
     * Static identity wrapper — retained for UnsoundCafdStylePlugin contrast test.
     * The corrupt oracle identifies this by name ("wrapper id") and applies
     * "never-alias ARunner ARunner" without a consistency check.
     * Static methods are NOT context-separated by 2-obj, so xa and xb always
     * see {ARunner, BRunner} regardless of the CS level.
     */
    static Runner id(Runner r) {
        return r;
    }

    public static void main(String[] args) {
        ARunner a = new ARunner();
        BRunner b = new BRunner();

        // Instance wrapper calls: RunnerBox.get() is an instance method, so
        // 2-obj separates box1 and box2 heap contexts.
        // Under 2-obj: wa → {ARunner}, wb → {BRunner}  (size 1 each)
        // Under CI:    wa → {ARunner, BRunner}, wb → {ARunner, BRunner}  (size 2 each)
        // This precision gap drives the avgPtsSize inequality in the sound arm1 test.
        RunnerBox box1 = new RunnerBox();
        RunnerBox box2 = new RunnerBox();
        box1.put(a);
        box2.put(b);
        Runner wa = box1.get();
        Runner wb = box2.get();
        wa.run();
        wb.run();

        // Static id() calls: context NOT separated — xa and xb always point to
        // {ARunner, BRunner} under both CI and 2-obj (arg-group propagation for
        // UnsoundCafdStylePlugin uses these alloc-group mappings: a→ARunner, b→BRunner).
        Runner xa = id(a);
        Runner xb = id(b);
        xa.run();
        xb.run();
    }
}
