// Benchmark for the Arm 3 (neuro-symbolic) robustness sweep — REDESIGNED for
// SOUND per-callsite heap cloning ("CAFD done right"). Mirrors B3's WrapperProgram.
//
// make() is a genuine FRESH-ALLOCATION WRAPPER: it returns `new Data()` on every
// path, with no escaping side effect. WrapperDetector confirms it.
//
// Under context-INSENSITIVE analysis, make() has a single abstract Data object,
// so the results of the two call sites (a and b) point to the SAME object and
// alias each other:
//   pts(a) = pts(b) = {Data@make}   =>   a and b MAY-ALIAS  (1 abstract object)
//
// A CORRECT LLM proposal ("make" is a fresh wrapper), confirmed by the detector,
// clones make() per call site, so a and b point to DISTINCT abstract objects:
//   pts(a) = {Data@callsite1}, pts(b) = {Data@callsite2}   =>   a, b DON'T alias
//   (2 abstract objects, fewer may-alias pairs). This refined heap is the p=0
//   reference.
//
// A WRONG proposal (any error rate > 0 that flips make YES->NO, or proposes a
// non-wrapper the detector rejects) simply does NOT clone, so the analysis falls
// back to the SOUND CI super-set (a and b alias again). Cloning only ever ADDS
// abstract objects and SPLITS alias sets — it never drops a real call edge — so
// recall vs the p=0 reference call-graph stays 1.0 at every error rate. Arm 3 is
// sound by construction.

class Data {
}

public class Arm3Sweep {

    /** Fresh-allocation wrapper — cloned per call site when confirmed. */
    static Data make() {
        return new Data();
    }

    public static void main(String[] args) {
        Data a = make();   // call site 1
        Data b = make();   // call site 2
        sink(a);
        sink(b);
    }

    static void sink(Object o) {
    }
}
