// Soundness regression for Arm 3 (neuro-symbolic) — the MULTI-INFLOW hole.
//
// The wrapper argument `arg` has TWO inflows:
//   arg = new A();     // group-A object
//   arg = makeB();     // group-B object (B <: A) via a method return
// Tai-e's pointer analysis is flow-INSENSITIVE, so pts(arg) = {A-obj, B-obj}.
//
// The identity wrapper id() therefore legitimately propagates BOTH objects to
// its result `lhs`: pts(lhs) = {A-obj, B-obj}. Hence the virtual call lhs.foo()
// MUST dispatch to BOTH A.foo() and B.foo().
//
// A (wrong) LLM answer asserts `never-alias A B` + `wrapper id`. A naive
// disposer that trusts the purely-SYNTACTIC alloc-group of `arg` would see only
// the `new A()` inflow (the `arg = makeB()` inflow is a call, not a New/Copy),
// conclude arg-group == A, and ban ALL group-B objects from `lhs`. That DROPS
// the real B-obj from pts(lhs) and loses the real lhs.foo() -> B.foo() dispatch
// edge => UNSOUND.
//
// A sound disposer must gate the ban on the SOUND (context-insensitive)
// points-to set of `arg` (which contains a B-obj) and WITHHOLD the filter, so
// B is preserved. Soundness must hold despite the bad LLM fact.

class A {
    void foo() {
    }
}

class B extends A {
    void foo() {
    }
}

public class MultiInflowWrapper {

    static A makeB() {
        return new B();
    }

    static A id(A x) {
        return x;
    }

    public static void main(String[] args) {
        A arg = new A();
        arg = makeB();        // second inflow: arg now also holds a B-obj
        A lhs = id(arg);
        lhs.foo();            // must dispatch to A.foo() AND B.foo()
    }
}
