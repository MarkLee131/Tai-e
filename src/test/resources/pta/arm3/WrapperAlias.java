// Small wrapper program for Arm 3 (neuro-symbolic) integration test.
//
// Under context-INSENSITIVE pointer analysis, the identity wrapper id() is
// analyzed once: its parameter conflates {AObj, BObj} across the two call
// sites, so both wa and wb spuriously point to {AObj, BObj}. Hence:
//   - pts(wa) = {AObj, BObj}  (size 2)
//   - cast (AObj) wa  may fail (BObj could be there)
//
// An accepted never-alias(AObj, BObj) fact + identity-wrapper(id) judgment
// de-conflates the wrapper output: wa is filtered to its argument's group
// (AObj), so pts(wa) = {AObj} (size 1) and the cast is safe.

class AObj {
}

class BObj {
}

public class WrapperAlias {

    static Object id(Object o) {
        return o;
    }

    public static void main(String[] args) {
        Object a = new AObj();
        Object b = new BObj();
        Object wa = id(a);
        Object wb = id(b);
        AObj castA = (AObj) wa;
        BObj castB = (BObj) wb;
    }
}
