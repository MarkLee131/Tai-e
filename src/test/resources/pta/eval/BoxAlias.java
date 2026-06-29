// Canonical object-sensitivity benchmark: instance methods force context
// separation under 2-obj, conflation under context-insensitive analysis.
//
// Under context-INSENSITIVE analysis (B0), Box.get() is analysed once.
// The single summary sees this.value in {new A(), new B()}, so both
// variables x and y have points-to set {A-alloc, B-alloc} (size 2).
//
// Under 2-object-sensitive analysis (B1), the two Box allocation sites
// yield distinct heap contexts.  In the context of b1's allocation, get()
// returns only the A object; in the context of b2's allocation, only B.
// Hence x -> {A-alloc} (size 1) and y -> {B-alloc} (size 1), strictly
// reducing avgPtsSize relative to B0.
//
// The static fields sinkX/sinkY ensure that x and y are simultaneously
// live at the assignment statements, preventing the compiler from reusing
// a single bytecode slot for both locals (slot reuse would collapse their
// points-to sets under any sensitivity level).

class A {}

class B {}

class Box {
    private Object value;

    void set(Object v) {
        this.value = v;
    }

    Object get() {
        return this.value;
    }
}

public class BoxAlias {

    /** Sink for x's value — keeps x live until after y is assigned. */
    static Object sinkX;

    /** Sink for y's value — symmetric sink for y. */
    static Object sinkY;

    public static void main(String[] args) {
        Box b1 = new Box();
        Box b2 = new Box();
        b1.set(new A());
        b2.set(new B());
        // Assign both before using either: at the point "y = b2.get()",
        // x is still live (it will be read at sinkX = x below), so the
        // compiler allocates x and y to different local-variable slots.
        Object x = b1.get();
        Object y = b2.get();
        sinkX = x;
        sinkY = y;
    }
}
