// S2: the interface Class objs at a ServiceLoader.load site can arrive in DIFFERENT
// solver iterations. SvcA.class is a constant (first rounds); SvcB.class arrives via 3
// hops (later delta). Providers recorded for the late interface must still be delivered
// to the already-fired iterator()/next() chain — no stale snapshot.

public class ArmReflectionLateProvider {

    public static void main(String[] args) {
        Class<?> c;
        if (args.length > 0) {
            c = SvcA.class; // constant: present from the first rounds
        } else {
            c = hop3(); // SvcB: three hops → later iteration
        }
        for (Object o : java.util.ServiceLoader.load(c)) {
            ((Svc) o).run();
        }
    }

    static Class<?> hop3() {
        return hop2();
    }

    static Class<?> hop2() {
        return hop1();
    }

    static Class<?> hop1() {
        return SvcB.class;
    }
}

interface Svc {
    void run();
}

interface SvcA extends Svc {
}

interface SvcB extends Svc {
}

class ProvA implements SvcA {

    public void run() {
        aHit();
    }

    static void aHit() {
    }
}

class ProvB implements SvcB {

    public void run() {
        bHit();
    }

    static void bHit() {
    }
}
