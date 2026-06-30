// Fixture for arm② §11 step-2 evidence collection.
// Class/method names are non-constant (built via StringBuilder) so the base
// string-constant inference cannot resolve them -> the newInstance() and
// invoke() sites are residual (unresolved) and carry use-site evidence:
//   - newInstance() result is downcast to (Animal)
//   - invoke()'s receiver argument is of type Animal, result downcast to (String)

public class ArmReflectionEvidence {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName(cname());
        Animal a = (Animal) c.newInstance();   // NEW_INSTANCE, downcast = Animal
        a.toString();

        Animal recv = new Dog();
        java.lang.reflect.Method m = c.getMethod(mname(), String.class);
        Object res = m.invoke(recv, "hi");      // INVOKE, receiver = recv : Animal
        String r = (String) res;                // result downcast = String
        r.length();

        Object u = new Cat();                   // load Cat (NOT a subtype of Animal)
        u.hashCode();
    }

    static String cname() {
        StringBuilder s = new StringBuilder();
        s.append("D");
        s.append("og");
        return s.toString();
    }

    static String mname() {
        StringBuilder s = new StringBuilder();
        s.append("sp");
        s.append("eak");
        return s.toString();
    }
}

class Animal {
    String speak(String s) {
        return s;
    }
}

class Dog extends Animal {
}

class Cat {
}
