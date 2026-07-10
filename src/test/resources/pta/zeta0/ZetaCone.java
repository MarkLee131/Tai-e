/**
 * ζ0 classifier fixture: one dispatch per method, each exercising one arm of
 * the tracked/open receiver classification (see ConeClosureFeasibilityProbe).
 */
interface ZShape {
    int area();
}

class ZSquare implements ZShape {
    public int area() {
        return 4;
    }
}

class ZCircle implements ZShape {
    public int area() {
        return 3;
    }
}

public class ZetaCone {

    public static void main(String[] args) {
        trackedSimple();
        trackedCopy();
        openParam(new ZSquare());
        openEscape();
        openMultiDef(args.length > 0);
        trackedEscapeAfter();
    }

    /** TRACKED: unique def is a New of a concrete class, never escapes. */
    static int trackedSimple() {
        ZSquare s = new ZSquare();
        return s.area();
    }

    /** TRACKED: reached through a Copy chain (upcast to the interface). */
    static int trackedCopy() {
        ZSquare s = new ZSquare();
        ZShape t = s;
        return t.area();
    }

    /** OPEN: receiver is a parameter (caller-supplied). */
    static int openParam(ZShape s) {
        return s.area();
    }

    /** OPEN: receiver passed as an argument to a call BEFORE the dispatch. */
    static int openEscape() {
        ZSquare s = new ZSquare();
        register(s);
        return s.area();
    }

    /** OPEN: two reaching defs (New in each branch). */
    static int openMultiDef(boolean b) {
        ZShape s;
        if (b) {
            s = new ZSquare();
        } else {
            s = new ZCircle();
        }
        return s.area();
    }

    /** TRACKED: the escaping call is AFTER the dispatch. */
    static int trackedEscapeAfter() {
        ZSquare s = new ZSquare();
        int a = s.area();
        register(s);
        return a;
    }

    static void register(Object o) {
    }
}
