// Test program for pta.arm2.TargetFilterTest.
// IBase declares run(); Foo and Baz override it. There is NO class "Bogus".
// IfaceTargets.main contains a virtual invoke "b.run()" whose method
// reference carries the expected subsignature run() used by TargetFilter.

class IBase {
    void run() {
    }
}

class Foo extends IBase {
    @Override
    void run() {
    }
}

class Baz extends IBase {
    @Override
    void run() {
    }
}

public class IfaceTargets {

    public static void main(String[] args) {
        IBase b = make();
        b.run();
    }

    static IBase make() {
        return new Foo();
    }
}
