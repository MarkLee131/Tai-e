// The name var of a reflective forName may point to BOTH a (bogus) string constant
// and an Unknown string at once — exactly DaCapo's dacapo.TestHarness.findClass, where
// config.className points to a merged bag of parser constants ("args","bytes",...) PLUS
// an Unknown merged string. arm② must still consult the LLM for the Unknown part; a
// stray constant must not suppress it.

public class ArmReflectionMixedName {

    public static void main(String[] args) throws Exception {
        String name;
        if (args.length > 0) {
            name = "args"; // a bogus constant (not a class) — sets the "known" flag
        } else {
            name = new String(new char[]{'x'}); // Unknown string (no constant value)
        }
        Class<?> c = Class.forName(name);
        c.newInstance();
    }
}

class Mixed {

    public Mixed() {
        hit();
    }

    static void hit() {
    }
}
