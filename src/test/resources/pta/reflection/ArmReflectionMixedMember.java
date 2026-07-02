// S3: at a getMethod site the name var may point to BOTH a (bogus) distinguished string
// constant AND an Unknown string. The constant must not suppress the LLM for the Unknown
// part (same contract classForName already implements). "decoy" is guaranteed to be a
// distinguished constant because Target declares a method of that name (IsReflectionString).

public class ArmReflectionMixedMember {

    public static void main(String[] args) throws Exception {
        Target t = new Target();
        String name;
        if (args.length > 0) {
            name = "decoy"; // distinguished constant → resolves Target.decoy
        } else {
            name = new String(new char[]{'x'}); // Unknown (input-dependent)
        }
        java.lang.reflect.Method m = Target.class.getMethod(name);
        m.invoke(t);
    }
}

class Target {

    public void decoy() {
    }

    public void realRun() {
        hit();
    }

    static void hit() {
    }
}
